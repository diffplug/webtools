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

import com.github.eirslett.maven.plugins.frontend.lib.ProxyConfig;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.CacheableTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskProvider;

/**
 * Installs a specific version of node.js and npm,
 * Runs 'npm ci' to install the packages, and then `gulp blah`
 * to run whatever gulp tasks you want.
 */
public class NodePlugin implements Plugin<Project> {
	private static final String EXTENSION_NAME = "node";

	public static class Extension {
		private final Project project;
		private final SetupCleanupNode setup = new SetupCleanupNode();

		public Extension(Project project) {
			this.project = Objects.requireNonNull(project);
		}

		public TaskProvider<?> npm_run(String name, Action<NpmRunTask> taskConfig) {
			return project.getTasks().register("npm_run_" + name.replace(':', '-'), NpmRunTask.class, task -> {
				task.npmTaskName = name;
				try {
					setup.nodeVersion = nvmRc(findNvmRc(project.getProjectDir()));
					setup.npmVersion = "provided";
				} catch (IOException e) {
					throw new RuntimeException(e);
				}

				task.getSetup().set(setup);
				task.getProjectDir().set(project.getProjectDir());
				task.getInputs().file("package-lock.json").withPathSensitivity(PathSensitivity.RELATIVE);

				task.getInputs().property("nodeVersion", setup.nodeVersion);
				task.getInputs().property("npmVersion", setup.npmVersion);
				taskConfig.execute(task);
			});
		}
	}

	@CacheableTask
	public abstract static class NpmRunTask extends DefaultTask {
		public String npmTaskName;
		private TreeMap<String, String> environment = new TreeMap<>();

		@Input
		public String getNpmTaskName() {
			return npmTaskName;
		}

		@Input
		public TreeMap<String, String> getEnvironment() {
			return environment;
		}

		@Internal
		public abstract Property<SetupCleanupNode> getSetup();

		@Internal
		public abstract DirectoryProperty getProjectDir();

		@TaskAction
		public void npmCiRunTask() throws Exception {
			SetupCleanupNode setup = getSetup().get();
			File projectDir = getProjectDir().get().getAsFile();
			
			System.out.println("=== NPM Task: " + npmTaskName + " ===");
			System.out.println("Installing Node.js dependencies...");
			
			// install node, npm, and package-lock.json
			setup.start(projectDir);
			
			System.out.println("Running: npm run " + npmTaskName);
			if (!environment.isEmpty()) {
				System.out.println("Environment variables:");
				environment.forEach((key, value) -> System.out.println("  " + key + "=" + value));
			}
			System.out.println();
			
			try {
				// Use ProcessBuilder for direct console output instead of NpmRunner
				File installDir = new File(projectDir, "build/node-install");
				File npmExe;
				
				// Based on frontend-maven-plugin structure, npm is directly in the node directory
				File nodeExe = new File(installDir, "node/node");
				if (System.getProperty("os.name").toLowerCase().contains("win")) {
					nodeExe = new File(installDir, "node/node.exe");
					npmExe = new File(installDir, "node/npm.cmd");
				} else {
					npmExe = new File(installDir, "node/npm");
				}
				
				System.out.println("Using node executable: " + nodeExe.getAbsolutePath());
				System.out.println("Node executable exists: " + nodeExe.exists());
				System.out.println("Using npm executable: " + npmExe.getAbsolutePath());
				System.out.println("NPM executable exists: " + npmExe.exists());
				
				ProcessBuilder processBuilder = new ProcessBuilder(npmExe.getAbsolutePath(), "run", npmTaskName);
				
				processBuilder.directory(projectDir);
				processBuilder.environment().putAll(environment);
				
				Process process = processBuilder.start();
				
				// Buffer output to only show on failure
				List<String> stdoutLines = new ArrayList<>();
				List<String> stderrLines = new ArrayList<>();
				
				// Create threads to read stdout and stderr concurrently
				CompletableFuture<Void> stdoutFuture = CompletableFuture.runAsync(() -> {
					try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
						String line;
						while ((line = reader.readLine()) != null) {
							synchronized (stdoutLines) {
								stdoutLines.add(line);
							}
						}
					} catch (IOException e) {
						synchronized (stdoutLines) {
							stdoutLines.add("Error reading stdout: " + e.getMessage());
						}
					}
				});
				
				CompletableFuture<Void> stderrFuture = CompletableFuture.runAsync(() -> {
					try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
						String line;
						while ((line = reader.readLine()) != null) {
							synchronized (stderrLines) {
								stderrLines.add(line);
							}
						}
					} catch (IOException e) {
						synchronized (stderrLines) {
							stderrLines.add("Error reading stderr: " + e.getMessage());
						}
					}
				});
				
				int exitCode = process.waitFor();
				
				// Wait for output streams to finish
				CompletableFuture.allOf(stdoutFuture, stderrFuture).join();
				
				System.out.println();
				if (exitCode == 0) {
					System.out.println("✓ NPM task '" + npmTaskName + "' completed successfully");
				} else {
					System.out.println("✗ NPM task '" + npmTaskName + "' failed with exit code " + exitCode);
					System.out.println();
					System.out.println("=== NPM OUTPUT ===");
					
					// Print stdout if there's any
					if (!stdoutLines.isEmpty()) {
						System.out.println("STDOUT:");
						for (String line : stdoutLines) {
							System.out.println(line);
						}
						System.out.println();
					}
					
					// Print stderr if there's any
					if (!stderrLines.isEmpty()) {
						System.out.println("STDERR:");
						for (String line : stderrLines) {
							System.out.println(line);
						}
					}
					
					System.out.println("==================");
					throw new RuntimeException("npm run " + npmTaskName + " failed with exit code " + exitCode);
				}
			} catch (Exception e) {
				System.out.println();
				System.out.println("✗ NPM task '" + npmTaskName + "' failed");
				System.out.println("Command: npm run " + npmTaskName);
				System.out.println("Error: " + e.getMessage());
				throw e;
			}
		}
	}

	@Override
	public void apply(Project project) {
		project.getExtensions().create(EXTENSION_NAME, Extension.class, project);
	}

	private static String nvmRc(File file) throws IOException {
		String str = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).trim();
		return "v" + str;
	}

	private static File findNvmRc(File projectDir) {
		File nvmRc = new File(projectDir, ".nvmrc");
		if (nvmRc.exists()) {
			return nvmRc;
		}
		nvmRc = new File(projectDir.getParentFile(), ".nvmrc");
		if (nvmRc.exists()) {
			return nvmRc;
		}
		throw new IllegalArgumentException("Could not find .nvmrc in " + projectDir + " or its parent.");
	}
}
