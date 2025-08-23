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

import gg.jte.ContentType;
import gg.jte.TemplateConfig;
import gg.jte.compiler.TemplateParser;
import gg.jte.compiler.TemplateParserVisitorAdapter;
import gg.jte.compiler.TemplateType;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import org.gradle.api.DefaultTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileType;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.ChangeType;
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

		var jteModelsTask = project.getTasks().register("jteModels", RenderModelClasses.class, task -> {
			var jteModels = new File(project.getLayout().getBuildDirectory().getAsFile().get(), "jte-models");
			main.getJava().srcDir(jteModels);
			task.getOutputDir().set(jteModels);
			task.getInputDir().set((File) extension.call("getSourceDirectory").call("get").call("toFile").get());
			task.getPackageName().set((Property<String>) extension.call("getPackageName").get());
			task.getContentType().set((Property<ContentType>) extension.call("getContentType").get());
		});
		project.getTasks().named("compileKotlin").configure(task -> task.dependsOn(jteModelsTask));
	}

	public static abstract class RenderModelClasses extends DefaultTask {
		@Incremental
		@PathSensitive(PathSensitivity.RELATIVE)
		@InputDirectory
		abstract DirectoryProperty getInputDir();

		@OutputDirectory
		abstract DirectoryProperty getOutputDir();

		@Input
		abstract Property<String> getPackageName();

		@Input
		abstract Property<ContentType> getContentType();

		@TaskAction
		public void render(InputChanges changes) throws IOException {
			var templateConfig = new TemplateConfig(getContentType().get(), getPackageName().get());
			var renderer = new JteRenderer(getInputDir().getAsFile().get(), templateConfig);
			for (var change : changes.getFileChanges(getInputDir())) {
				if (change.getFileType() == FileType.DIRECTORY) {
					return;
				}
				String name = change.getFile().getName();
				if (!name.endsWith(".jte") && !name.endsWith(".kte")) {
					continue;
				}
				var targetFileJte = getOutputDir().file(change.getNormalizedPath()).get().getAsFile().getAbsolutePath();
				var targetFile = new File(targetFileJte.substring(0, targetFileJte.length() - 4) + ".kt");
				if (change.getChangeType() == ChangeType.REMOVED) {
					targetFile.delete();
				} else {
					targetFile.getParentFile().mkdirs();
					Files.write(targetFile.toPath(), renderer.render(change.getFile()).getBytes(StandardCharsets.UTF_8));
				}
			}
		}
	}

	static String convertJavaToKotlin(String javaType) {
		if (javaType.equals("boolean")) {
			return "Boolean";
		} else if (javaType.equals("int")) {
			return "Int";
		} else {
			// e.g. `@param Result<?> records` -> `val records: Result<*>`
			return javaType
					.replace("<?>", "<*>")
					.replace("java.util.Collection", "Collection")
					.replace("java.util.List", "List")
					.replace("java.util.Map", "Map")
					.replace("java.util.Set", "Set");
		}
	}

	static class JteRenderer {
		final File rootDir;
		final TemplateConfig config;

		JteRenderer(File rootDir, TemplateConfig config) {
			this.rootDir = rootDir;
			this.config = config;
		}

		String render(File file) throws IOException {
			var pkg = file.getParentFile().getAbsolutePath().substring(rootDir.getAbsolutePath().length() + 1).replace(File.separatorChar, '.');
			var name = file.getName();
			var lastDot = name.lastIndexOf('.');
			name = name.substring(0, lastDot);
			String ext = file.getName().substring(lastDot);

			var imports = new LinkedHashSet<String>();
			imports.add("gg.jte.TemplateEngine");
			imports.add("gg.jte.TemplateOutput");
			var params = new LinkedHashMap<String, String>();

			new TemplateParser(Files.readString(file.toPath()), TemplateType.Template, new TemplateParserVisitorAdapter() {
				@Override
				public void onImport(String importClass) {
					imports.add(importClass.replace("static ", ""));
				}

				@Override
				public void onParam(String parameter) {
					var idxOfColon = parameter.indexOf(':');
					if (idxOfColon == -1) { // .jte
						// lastIndexOf accounts for valid multiple spaces, e.g `Map<String, String> featureMap`
						var spaceIdx = parameter.lastIndexOf(' ');
						var type = parameter.substring(0, spaceIdx).trim();
						var name = parameter.substring(spaceIdx + 1).trim();
						if (name.endsWith("Nullable")) {
							type += "?";
						}
						params.put(name, convertJavaToKotlin(type));
					} else { // .kte
						var name = parameter.substring(0, idxOfColon).trim();
						var type = parameter.substring(idxOfColon + 1).trim();
						params.put(name, type);
					}
				}
			}, config).parse();

			var builder = new StringBuilder();
			builder.append("package " + pkg + "\n");
			builder.append("\n");
			for (var imp : imports) {
				builder.append("import " + imp + "\n");
			}
			builder.append("\n");
			builder.append("class " + name + "(\n");
			params.forEach((paramName, type) -> {
				builder.append("\tval " + paramName + ": " + type + ",\n");
			});
			builder.append("\t) : common.JteModel {\n");
			builder.append("\n");
			builder.append("\toverride fun render(engine: TemplateEngine, output: TemplateOutput) {\n");
			builder.append("\t\tengine.render(\"" + pkg.replace('.', '/') + "/" + name + ext + "\", mapOf(\n");
			params.forEach((paramName, type) -> {
				builder.append("\t\t\t\"" + paramName + "\" to " + paramName + ",\n");
			});
			builder.append("\t\t), output)\n");
			builder.append("\t}\n");
			builder.append("}");
			return builder.toString();
		}
	}
}
