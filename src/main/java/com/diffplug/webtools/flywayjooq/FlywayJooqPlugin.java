package com.diffplug.webtools.flywayjooq;

import org.gradle.api.DefaultTask;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskProvider;
import org.jooq.codegen.gradle.CodegenPluginExtension;

import javax.inject.Inject;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This plugin spools up a fresh postgres session,
 * runs flyway on it, then runs jooq on the result.
 * The postgres stays up so that the flyway result
 * can be used as a template database for testing.
 */
public class FlywayJooqPlugin implements Plugin<Project> {
	private static final String EXTENSION_NAME = "flywayJooq";

	public static class Extension extends CodegenPluginExtension {
		@Inject
		public Extension(
			ObjectFactory objects,
			ProjectLayout layout
		) {
			super(objects, layout);
		}

		public final SetupCleanupDockerFlyway setup = new SetupCleanupDockerFlyway();

		/** Ensures a database with a template prepared by Flyway is available. */
		public void neededBy(TaskProvider<?> taskProvider) {
			taskProvider.configure(this::neededBy);
		}

		/** Ensures a database with a template prepared by Flyway is available. */
		public void neededBy(Task task) {
			task.dependsOn(DockerUp.TASK_NAME);
			task.getInputs().file(setup.dockerComposeFile).withPathSensitivity(PathSensitivity.RELATIVE);
			task.getInputs().dir(setup.flywayMigrations).withPathSensitivity(PathSensitivity.RELATIVE);
		}
	}

	@Override
	public void apply(Project project) {
		// FlywayPlugin needs to be applied first
		project.getPlugins().apply(JavaBasePlugin.class);

		Extension extension = project.getExtensions().create(EXTENSION_NAME, Extension.class);
		extension.configuration(a -> {});
		extension.setup.dockerPullOnStartup = !project.getGradle().getStartParameter().isOffline();

		// force all jooq versions to match
		String jooqVersion = detectJooqVersion();
		project.getConfigurations().all(config -> {
			config.resolutionStrategy(strategy -> {
				strategy.eachDependency(details -> {
					String group = details.getRequested().getGroup();
					String name = details.getRequested().getName();
					if (group.equals("org.jooq") && name.startsWith("jooq")) {
						details.useTarget(group + ":" + name + ":" + jooqVersion);
					}
				});
			});
		});

		// create a jooq task, which will be needed by all compilation tasks
		TaskProvider<JooqTask> jooqTask = project.getTasks().register("jooq", JooqTask.class, task -> {
			task.setup = extension.setup;
			var generator = extension.getExecutions().maybeCreate("").getConfiguration().getGenerator();
			task.generatorConfig = generator;
			task.getGeneratedSource().set(project.file(generator.getTarget().getDirectory()));
		});
		extension.neededBy(jooqTask);
		project.getTasks().named(JavaPlugin.COMPILE_JAVA_TASK_NAME).configure(task -> {
			task.dependsOn(jooqTask);
		});

		project.getTasks().register(DockerDown.TASK_NAME, DockerDown.class, task -> {
			task.getSetupCleanup().set(extension.setup);
			task.getProjectDir().set(project.getProjectDir());
		});
		project.getTasks().register(DockerUp.TASK_NAME, DockerUp.class, task -> {
			task.getSetupCleanup().set(extension.setup);
			task.getProjectDir().set(project.getProjectDir());
			task.mustRunAfter(DockerDown.TASK_NAME);
		});
	}

	public abstract static class DockerUp extends DefaultTask {
		private static final String TASK_NAME = "dockerUp";

		@Internal
		public abstract DirectoryProperty getProjectDir();

		@Internal
		public abstract Property<SetupCleanupDockerFlyway> getSetupCleanup();

		@TaskAction
		public void dockerUp() throws Exception {
			getSetupCleanup().get().start(getProjectDir().get().getAsFile());
		}
	}

	public abstract static class DockerDown extends DefaultTask {
		private static final String TASK_NAME = "dockerDown";

		@Internal
		public abstract DirectoryProperty getProjectDir();

		@Internal
		public abstract Property<SetupCleanupDockerFlyway> getSetupCleanup();

		@TaskAction
		public void dockerDown() throws Exception {
			getSetupCleanup().get().forceStop(getProjectDir().get().getAsFile());
		}
	}


	/** Detects the jooq version. */
	private static String detectJooqVersion() {
		URLClassLoader loader = (URLClassLoader) FlywayJooqPlugin.class.getClassLoader();
		for (URL url : loader.getURLs()) {
			Pattern pattern = Pattern.compile("(.*)/jooq-([0-9,.]*?).jar$");
			Matcher matcher = pattern.matcher(url.getPath());
			if (matcher.matches()) {
				return matcher.group(2);
			}
		}
		throw new IllegalStateException("Unable to detect jooq version.");
	}
}
