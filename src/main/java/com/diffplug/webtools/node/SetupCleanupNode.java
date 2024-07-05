/*
 * Copyright (C) 2024 DiffPlug
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

import com.github.eirslett.maven.plugins.frontend.lib.FrontendPluginFactory;
import com.github.eirslett.maven.plugins.frontend.lib.InstallationException;
import com.github.eirslett.maven.plugins.frontend.lib.ProxyConfig;
import com.github.eirslett.maven.plugins.frontend.lib.TaskRunnerException;
import java.io.File;
import java.io.Serializable;
import java.nio.file.Files;
import java.util.Collections;

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

	private static class Impl extends SetupCleanup<SetupCleanupNode> {
		@Override
		protected void doStart(SetupCleanupNode key) throws TaskRunnerException, InstallationException {
			ProxyConfig proxyConfig = new ProxyConfig(Collections.emptyList());
			FrontendPluginFactory factory = key.factory();
			factory.getNodeInstaller(proxyConfig)
					.setNodeVersion(key.nodeVersion)
					.setNpmVersion(key.npmVersion)
					.install();
			factory.getNpmRunner(proxyConfig, null)
					.execute("ci", null);
		}

		@Override
		protected void doStop(SetupCleanupNode key) throws Exception {

		}
	}
}
