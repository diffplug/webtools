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
package com.diffplug.webtools.flywayjooq;

import com.diffplug.common.base.Preconditions;
import com.diffplug.common.base.Throwables;
import java.io.File;
import java.net.ConnectException;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.tasks.*;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Logging;

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
