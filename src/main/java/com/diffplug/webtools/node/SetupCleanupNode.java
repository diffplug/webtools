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

import com.diffplug.common.swt.os.OS;
import com.github.eirslett.maven.plugins.frontend.lib.FrontendPluginFactory;
import com.github.eirslett.maven.plugins.frontend.lib.InstallationException;
import com.github.eirslett.maven.plugins.frontend.lib.ProxyConfig;
import com.github.eirslett.maven.plugins.frontend.lib.TaskRunnerException;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.gradle.api.GradleException;

class SetupCleanupNode implements Serializable {
	public String nodeVersion;
	public String npmVersion;
	private File workingDir, installDir;
	@SuppressWarnings("unused") // used for serialized equality
	private byte[] packageLockJson;

	public void start(File projectDir) throws Exception {
		workingDir = projectDir;
		installDir = new File(projectDir, "build/node-install");
		packageLockJson = Files.readAllBytes(workingDir.toPath().resolve("package-lock.json"));
		new Impl().start(keyFile(projectDir), this);
	}

	FrontendPluginFactory factory() {
		return new FrontendPluginFactory(workingDir, installDir);
	}

	private static File keyFile(File projectDir) {
		return new File(projectDir, "build/node_modules/.gradle-state");
	}

	public void executeNpmCommand(String command, String... args) throws Exception {
		List<String> commandArgs = new ArrayList<>();
		commandArgs.add(command);
		for (String arg : args) {
			commandArgs.add(arg);
		}
		executeNpmCommand(commandArgs, Collections.emptyMap());
	}

	public void executeNpmCommand(List<String> commandArgs, Map<String, String> environment) throws Exception {
		// Use ProcessBuilder for direct console output instead of NpmRunner
		File npmExe;
		if (System.getProperty("os.name").toLowerCase().contains("win")) {
			npmExe = new File(installDir, "node/npm.cmd");
		} else {
			npmExe = new File(installDir, "node/npm");
		}

		List<String> fullCommand = new ArrayList<>();
		fullCommand.add(npmExe.getAbsolutePath());
		fullCommand.addAll(commandArgs);

		ProcessBuilder processBuilder = new ProcessBuilder(fullCommand);
		processBuilder.directory(workingDir);

		addNodeToPath(processBuilder, installDir);
		processBuilder.environment().putAll(environment);
		Process process = processBuilder.start();

		// Buffer output to only show on failure
		List<String> stdoutLines = new ArrayList<>();
		List<String> stderrLines = new ArrayList<>();

		// Create threads to read stdout and stderr concurrently
		CompletableFuture<Void> stdoutFuture = readStream(process.getInputStream(), stdoutLines, "stdout");
		CompletableFuture<Void> stderrFuture = readStream(process.getErrorStream(), stderrLines, "stderr");
		int exitCode = process.waitFor();
		CompletableFuture.allOf(stdoutFuture, stderrFuture).join();
		if (exitCode == 0) {
			return;
		}

		var cmd = new StringBuilder().append("> npm ").append(String.join(" ", commandArgs)).append(" FAILED\n");
		environment.forEach((key, value) -> cmd.append("  env ").append(key).append("=").append(value).append("\n"));
		for (String line : stdoutLines) {
			cmd.append(line).append("\n");
		}
		for (String line : stderrLines) {
			cmd.append(line).append("\n");
		}
		throw new GradleException(cmd.toString());
	}

	private static CompletableFuture<Void> readStream(InputStream inputStream, List<String> outputLines, String streamName) {
		return CompletableFuture.runAsync(() -> {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
				String line;
				while ((line = reader.readLine()) != null) {
					synchronized (outputLines) {
						outputLines.add(line);
					}
				}
			} catch (IOException e) {
				synchronized (outputLines) {
					outputLines.add("Error reading " + streamName + ": " + e.getMessage());
				}
			}
		});
	}

	private static void addNodeToPath(ProcessBuilder processBuilder, File installDir) {
		// Add node binary directory to PATH for npm to find node executable
		File nodeDir = new File(installDir, "node");
		String currentPath = processBuilder.environment().get("PATH");
		if (currentPath == null) {
			currentPath = processBuilder.environment().get("Path"); // Windows
		}
		String nodeBinPath = nodeDir.getAbsolutePath();
		String pathSeparator = System.getProperty("path.separator");
		String newPath = nodeBinPath + pathSeparator + (currentPath != null ? currentPath : "");
		processBuilder.environment().put("PATH", newPath);
	}

	private static class Impl extends SetupCleanup<SetupCleanupNode> {
		@Override
		protected void doStart(SetupCleanupNode key) throws TaskRunnerException, InstallationException, Exception {
			ProxyConfig proxyConfig = new ProxyConfig(Collections.emptyList());
			FrontendPluginFactory factory = key.factory();
			factory.getNodeInstaller(proxyConfig)
					.setNodeVersion(key.nodeVersion)
					.setNpmVersion(key.npmVersion)
					.install();
			if (OS.getNative().isWindows()) {
				// copy npm.cmd as a windows workaround
				try {
					Files.copy(key.installDir.toPath().resolve("node/node_modules/npm/bin/npm.cmd"),
							key.installDir.toPath().resolve("node/npm.cmd"),
							StandardCopyOption.REPLACE_EXISTING);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			}
			key.executeNpmCommand("ci");
		}

		@Override
		protected void doStop(SetupCleanupNode key) throws Exception {

		}
	}
}
