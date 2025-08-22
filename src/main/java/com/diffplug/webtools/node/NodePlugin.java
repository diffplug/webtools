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

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import org.gradle.api.*;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.work.DisableCachingByDefault;

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
				task.getInputs().file("package.json").withPathSensitivity(PathSensitivity.RELATIVE);
				task.getInputs().file("package-lock.json").withPathSensitivity(PathSensitivity.RELATIVE);

				task.getInputs().property("nodeVersion", setup.nodeVersion);
				task.getInputs().property("npmVersion", setup.npmVersion);
				taskConfig.execute(task);
				task.getOutputs().cacheIf(spec -> !task.getOutputs().getFiles().isEmpty());
			});
		}
	}

	@DisableCachingByDefault(because = "Add `outputs.[dir|file](...)` (and necessary inputs) in the configuration block to enable caching.")
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
			// install node, npm, and package-lock.json
			setup.start(projectDir);

			// Use the new executeNpmCommand method
			List<String> commandArgs = new ArrayList<>();
			commandArgs.add("run");
			commandArgs.add(npmTaskName);
			setup.executeNpmCommand(commandArgs, environment);
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
