/*
 * Copyright (C) 2024-2025 DiffPlug
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.diffplug.webtools.node;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.util.Arrays;

public abstract class SetupCleanup<K> {
	public void start(File keyFile, K key) throws Exception {
		synchronized (key.getClass()) {
			byte[] required = toBytes(key);
			if (keyFile.exists()) {
				byte[] actual = Files.readAllBytes(keyFile.toPath());
				if (Arrays.equals(actual, required)) {
					// short-circuit if our state is already setup
					return;
				} else {
					Files.delete(keyFile.toPath());
					@SuppressWarnings("unchecked")
					K lastKey = (K) fromBytes(required);
					doStop(lastKey);
				}
			}
			// write out the key
			doStart(key);
			Files.createDirectories(keyFile.toPath().getParent());
			Files.write(keyFile.toPath(), required);
		}
	}

	protected abstract void doStart(K key) throws Exception;

	protected abstract void doStop(K key) throws Exception;

	private static byte[] toBytes(Object key) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream objectOutput = new ObjectOutputStream(bytes)) {
			objectOutput.writeObject(key);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		return bytes.toByteArray();
	}

	private static Object fromBytes(byte[] raw) throws ClassNotFoundException {
		ByteArrayInputStream bytes = new ByteArrayInputStream(raw);
		try (ObjectInputStream objectOutput = new ObjectInputStream(bytes)) {
			return objectOutput.readObject();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
