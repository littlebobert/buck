/*
 * Copyright 2018-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.rules.modern.builders;

import static org.junit.Assert.*;

import com.facebook.buck.remoteexecution.Protocol;
import com.facebook.buck.remoteexecution.Protocol.Digest;
import com.facebook.buck.remoteexecution.Protocol.FileNode;
import com.facebook.buck.remoteexecution.grpc.GrpcProtocol;
import com.facebook.buck.remoteexecution.util.LocalContentAddressedStorage;
import com.facebook.buck.remoteexecution.util.MerkleTreeNodeCache;
import com.facebook.buck.remoteexecution.util.MerkleTreeNodeCache.MerkleTreeNode;
import com.facebook.buck.testutil.TemporaryPaths;
import com.facebook.buck.util.function.ThrowingSupplier;
import com.google.common.base.Charsets;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.Futures;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class LocalContentAddressedStorageTest {
  @Rule public TemporaryPaths tmp = new TemporaryPaths();
  private LocalContentAddressedStorage storage;
  private Path storageDir;
  private final Protocol protocol = new GrpcProtocol();

  @Before
  public void setUp() {
    storageDir = tmp.getRoot().resolve("__storage__");
    storage = new LocalContentAddressedStorage(storageDir, protocol);
  }

  @Test
  public void canAddData() throws IOException {
    byte[] data = "hello world!".getBytes(Charsets.UTF_8);
    Digest digest = protocol.newDigest("myhashcode", data.length);
    Futures.getUnchecked(
        storage.addMissing(ImmutableMap.of(digest, () -> new ByteArrayInputStream(data))));
    assertDataEquals(data, getBytes(digest));
  }

  private byte[] getBytes(Digest digest) {
    try (InputStream dataStream = storage.getData(digest)) {
      return ByteStreams.toByteArray(dataStream);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void assertDataEquals(byte[] expected, byte[] actual) {
    assertEquals(new String(expected, Charsets.UTF_8), new String(actual, Charsets.UTF_8));
  }

  @Test
  public void presentDataIsNotAdded() throws IOException {
    byte[] data = "hello world!".getBytes(Charsets.UTF_8);
    Digest digest = protocol.newDigest("myhashcode", data.length);
    Futures.getUnchecked(
        storage.addMissing(ImmutableMap.of(digest, () -> new ByteArrayInputStream(data))));
    Futures.getUnchecked(
        storage.addMissing(
            ImmutableMap.of(
                digest,
                () -> {
                  throw new RuntimeException();
                })));
  }

  @Test
  public void addingAndMaterializingFullInputsWorks() throws IOException {
    Map<Path, FileNode> files = new HashMap<>();
    Path somePath = Paths.get("dir/some.path");
    byte[] someData = "hello world!".getBytes(Charsets.UTF_8);
    files.put(somePath, newFileNode(someData, somePath, false));
    Path otherPath = Paths.get("dir/other.path");
    byte[] otherData = "goodbye world!".getBytes(Charsets.UTF_8);
    files.put(otherPath, newFileNode(otherData, otherPath, false));

    MerkleTreeNodeCache nodeCache = new MerkleTreeNodeCache(protocol);
    MerkleTreeNode node = nodeCache.createNode(files, ImmutableMap.of());
    Digest rootDigest = nodeCache.getData(node).getDigest();

    ImmutableMap.Builder<Digest, ThrowingSupplier<InputStream, IOException>> requiredData =
        ImmutableMap.builder();

    requiredData.put(protocol.computeDigest(someData), () -> new ByteArrayInputStream(someData));
    requiredData.put(protocol.computeDigest(otherData), () -> new ByteArrayInputStream(otherData));
    nodeCache.forAllData(
        node,
        data ->
            requiredData.put(
                data.getDigest(),
                () -> new ByteArrayInputStream(protocol.toByteArray(data.getDirectory()))));

    Futures.getUnchecked(storage.addMissing(requiredData.build()));

    Path inputsDir = tmp.getRoot().resolve("inputs");
    storage.materializeInputs(inputsDir, rootDigest, Optional.empty());

    assertDataEquals(someData, Files.readAllBytes(inputsDir.resolve(somePath)));
    assertDataEquals(otherData, Files.readAllBytes(inputsDir.resolve(otherPath)));
  }

  private FileNode newFileNode(byte[] bytes, Path path, boolean isExecutable) {
    return protocol.newFileNode(
        protocol.computeDigest(bytes), path.getFileName().toString(), isExecutable);
  }
}
