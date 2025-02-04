/*
 * Copyright (C) 2023 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.copybara.util;

import static com.google.copybara.exception.ValidationException.checkCondition;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.HashCode;
import com.google.common.hash.HashFunction;
import com.google.common.io.MoreFiles;
import com.google.copybara.exception.ValidationException;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;

/**
 * SinglePatch represents the difference between what exists in the destination files and the output
 * of an import created by Copybara. This will only be different when using merge import mode,
 * because otherwise the destination files will be overwritten and there will be no difference.
 */
public class SinglePatch {

  private static final String header = ""
      + "# This file is generated by Copybara.\n"
      + "# Do not edit.\n";

  private static final String hashSectionDelimiterLine = "--hash-delimiter--";


  private final ImmutableMap<String, String> fileHashes;
  private final byte[] diffContent;

  public SinglePatch(ImmutableMap<String, String> fileHashes, byte[] diffContent) {
    this.fileHashes = fileHashes;
    this.diffContent = diffContent;
  }

  /**
   * Create a SinglePatch object from two folders containing separate versions of the repository.
   *
   * <p>The location of the two folders matters. The SinglePatch includes a diff between the two
   * folders which the parent of the destination will be used as the working directory for when
   * created. The locations of the folders will affect the paths that appear in the diff output.
   *
   * @param destination is the version containing all the destination only changes.
   * @param baseline is the version to diff against.
   */
  public static SinglePatch generateSinglePatch(Path destination, Path baseline,
      HashFunction hashFunction, Map<String, String> environment)
      throws IOException, InsideGitDirException {
    ImmutableMap.Builder<String, String> hashesBuilder = ImmutableMap.builder();
    Files.walkFileTree(destination, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
          throws IOException {
        HashCode hashCode = MoreFiles.asByteSource(file).hash(hashFunction);
        hashesBuilder.put(destination.relativize(file).toString(),
            hashCode.toString());
        return FileVisitResult.CONTINUE;
      }

    });
    byte[] diff = DiffUtil.diff(destination, baseline, false, environment);
    return new SinglePatch(hashesBuilder.build(), diff);
  }

  private static String mustReadLine(BufferedReader reader) throws IOException {
    String line = reader.readLine();
    if (line == null) {
      throw new IOException("failed to parse single patch file: unexpected end of file");
    }
    return line;
  }

  private static String mustReadUncommentedLine(BufferedReader reader) throws IOException {
    String line = mustReadLine(reader);
    while (line.startsWith("#")) {
      line = mustReadLine(reader);
    }
    return line;
  }

  public static SinglePatch fromBytes(byte[] bytes, HashFunction hashFunction)
      throws IOException, ValidationException {
    try (
        ByteArrayInputStream in = new ByteArrayInputStream(bytes);
        BufferedReader br = new BufferedReader(new InputStreamReader(in))
    ) {
      ImmutableMap.Builder<String, String> fileHashesBuilder = new ImmutableMap.Builder<>();
      String line = mustReadUncommentedLine(br);

      while (!line.equals(hashSectionDelimiterLine)) {
        List<String> splits = Splitter.on(": ").limit(2).splitToList(line);
        if (splits.size() != 2) {
          throw new IOException(
              "failed to parse single patch hashes: unexpected number of elements");
        }
        validateParsedPathValue(splits.get(0));
        validateParsedHashValue(splits.get(1), hashFunction);
        fileHashesBuilder.put(splits.get(0), splits.get(1));

        line = mustReadUncommentedLine(br);
      }

      line = br.readLine();
      ByteArrayOutputStream diffContentOut = new ByteArrayOutputStream();
      try (OutputStreamWriter diffContentWriter = new OutputStreamWriter(diffContentOut)) {
        while (line != null) {
          diffContentWriter.write(line + "\n");
          line = br.readLine();
        }
      }

      return new SinglePatch(fileHashesBuilder.build(), diffContentOut.toByteArray());
    }

  }

  public ImmutableMap<String, String> getFileHashes() {
    return ImmutableMap.copyOf(fileHashes);
  }

  public byte[] getDiffContent() {
    return Arrays.copyOf(diffContent, diffContent.length);
  }

  byte[] toBytes() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    // OutputStreamWriter for this part for idiomatic string writing
    try (OutputStreamWriter outWriter = new OutputStreamWriter(out)) {
      outWriter.write(header);

      for (Entry<String, String> entry : fileHashes.entrySet()) {
        outWriter.write(String.format("%s: %s\n",
            entry.getKey(),
            entry.getValue()));
      }
      outWriter.write(hashSectionDelimiterLine + "\n");
    }

    out.write(diffContent);

    return out.toByteArray();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }

    if (!(obj instanceof SinglePatch)) {
      return false;
    }

    SinglePatch that = (SinglePatch) obj;
    if (!this.fileHashes.equals(that.fileHashes)) {
      return false;
    }

    return Arrays.equals(this.diffContent, that.diffContent);
  }

  @Override
  public int hashCode() {
    return Objects.hash(fileHashes.hashCode(), Arrays.hashCode(this.diffContent));
  }

  @Override
  public String toString() {
    return String.format("%s\n%s\n", fileHashes, new String(diffContent, UTF_8));
  }

  private static void validateParsedPathValue(String path) throws ValidationException {
    try {
      var unused = Path.of(path);
    } catch (InvalidPathException e) {
      throw new ValidationException("Parsed path value is invalid.", e);
    }
  }

  private static void validateParsedHashValue(String hash, HashFunction hashFunction)
      throws ValidationException {
    try {
      var unused = HashCode.fromString(hash);
    } catch (IllegalArgumentException e) {
      throw new ValidationException("Parsed hash value is invalid.", e);
    }

    // HashCode.fromString() does not check if the length is wrong for any particular hash function.
    // Check legth of hash string against number of bits.
    int hashLengthInHex = hashFunction.bits() / 4;
    checkCondition(hash.length() == hashLengthInHex, String.format(
        "Parsed hash value has incorrect number of hex chars. Parsed length: %d. %s length: %d",
        hash.length(), hashFunction, hashLengthInHex));
  }
}
