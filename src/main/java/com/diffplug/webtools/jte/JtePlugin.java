/*
 * Copyright (C) 2025 DiffPlug
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
package com.diffplug.webtools.jte;

import static org.joor.Reflect.on;
import static org.joor.Reflect.onClass;

import java.io.File;
import java.io.IOException;
import org.gradle.api.DefaultTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.*;
import org.gradle.work.Incremental;
import org.gradle.work.InputChanges;

public class JtePlugin implements Plugin<Project> {
	@Override
	public void apply(Project project) {
		project.getPlugins().apply("gg.jte.gradle");
		project.getPlugins().apply("org.jetbrains.kotlin.jvm");
		var extension = on(project.getExtensions().getByType(onClass("gg.jte.gradle.JteExtension").type()));

		JavaPluginExtension javaPluginExtension = project.getExtensions().getByType(JavaPluginExtension.class);
		SourceSet main = javaPluginExtension.getSourceSets().findByName("main");

		project.getTasks().named("classes").configure(task -> {
			task.getInputs().dir(extension.call("getSourceDirectory"));
		});

		@SuppressWarnings("unchecked")
		var jteModelsTask = project.getTasks().register("jteModels", RenderModelClassesTask.class, task -> {
			var jteModels = new File(project.getLayout().getBuildDirectory().getAsFile().get(), "jte-models");
			main.getJava().srcDir(jteModels);
			task.getOutputDir().set(jteModels);
			task.getInputDir().set((File) extension.call("getSourceDirectory").call("get").call("toFile").get());
			task.getPackageName().set((Property<String>) extension.call("getPackageName").get());
			task.getContentType().set((Property<Enum<?>>) extension.call("getContentType").get());
		});
		project.getTasks().named("compileKotlin").configure(task -> task.dependsOn(jteModelsTask));
	}

	public static abstract class RenderModelClassesTask extends DefaultTask {
		@Incremental
		@PathSensitive(PathSensitivity.RELATIVE)
		@InputDirectory
		abstract DirectoryProperty getInputDir();

		@OutputDirectory
		abstract DirectoryProperty getOutputDir();

		@Input
		abstract Property<String> getPackageName();

		@Input
		abstract Property<Enum<?>> getContentType();

		@TaskAction
		public void render(InputChanges changes) throws IOException {
			onClass("com.diffplug.webtools.jte.JteRenderer").call("renderTask", this, changes);
		}
	}
}
