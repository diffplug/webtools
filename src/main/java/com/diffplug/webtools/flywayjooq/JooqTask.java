package com.diffplug.webtools.flywayjooq;

import com.diffplug.common.base.Preconditions;
import com.diffplug.common.base.Throwables;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.*;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Logging;

import java.io.File;
import java.net.ConnectException;

@CacheableTask
public abstract class JooqTask extends DefaultTask {
	SetupCleanupDockerFlyway setup;
	Generator generatorConfig;

	@Internal
	public SetupCleanupDockerFlyway getSetup() {
		return setup;
	}

	@OutputDirectory
	public abstract DirectoryProperty getGeneratedSource();

	@Input
	public Generator getGeneratorConfig() {
		return generatorConfig;
	}

	@TaskAction
	public void generate() throws Exception {
		String targetDir = generatorConfig.getTarget().getDirectory();
		Preconditions.checkArgument(!(new File(targetDir).isAbsolute()), "`generator.target.directory` must not be absolute, was `%s`", targetDir);
		// configure jooq to run against the db
		try {
			generatorConfig.getTarget().setDirectory(getGeneratedSource().get().getAsFile().getAbsolutePath());
			Configuration jooqConfig = new Configuration();
			jooqConfig.setGenerator(generatorConfig);
			jooqConfig.setLogging(Logging.TRACE);

			// write the config out to file
			GenerationTool tool = new GenerationTool();
			tool.setDataSource(setup.getConnection());
			tool.run(jooqConfig);
		} catch (Exception e) {
			var rootCause = Throwables.getRootCause(e);
			if (rootCause instanceof ConnectException) {
				throw new GradleException("Unable to connect to the database.  Is the docker container running?", e);
			} else {
				throw e;
			}
		} finally {
			generatorConfig.getTarget().setDirectory(targetDir);
		}
	}
}

