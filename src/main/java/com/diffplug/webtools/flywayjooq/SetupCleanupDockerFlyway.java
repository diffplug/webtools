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

import com.diffplug.common.base.Either;
import com.diffplug.common.base.Errors;
import com.diffplug.common.base.Throwables;
import com.diffplug.common.base.Throwing;
import com.diffplug.webtools.SetupCleanup;
import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.palantir.docker.compose.DockerComposeRule;
import com.palantir.docker.compose.configuration.ProjectName;
import com.palantir.docker.compose.configuration.ShutdownStrategy;
import com.palantir.docker.compose.connection.DockerPort;
import com.palantir.docker.compose.connection.waiting.HealthChecks;
import com.palantir.docker.compose.execution.DockerCompose;
import com.palantir.docker.compose.execution.DockerComposeExecArgument;
import com.palantir.docker.compose.execution.DockerComposeExecOption;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.gradle.api.GradleException;
import org.postgresql.ds.PGSimpleDataSource;
import webtools.Env;

public class SetupCleanupDockerFlyway implements Serializable {
	private static final long serialVersionUID = -8606504827780656288L;

	private static final String GITHUB_IP = "localhost";
	private static final int GITHUB_PORT = 5432;

	public File dockerComposeFile;
	public File dockerConnectionParams;
	public boolean dockerPullOnStartup = true;

	public File flywayMigrations;
	public File flywaySchemaDump;
	private TreeMap<String, byte[]> flywaySnapshot;
	private File buildDir;

	/** Saves the flywayMigrations, then starts docker (if necessary) and runs flyway. */
	void start(File projectDir) throws Exception {
		try {
			buildDir = new File(projectDir, "build");
			flywaySnapshot = new TreeMap<>();
			Path root = flywayMigrations.toPath();
			java.nio.file.Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					String path = root.relativize(file).toString();
					flywaySnapshot.put(path, java.nio.file.Files.readAllBytes(file));
					return FileVisitResult.CONTINUE;
				}
			});
			new Impl().start(keyFile(projectDir), this);
		} catch (Exception e) {
			var rootCause = Throwables.getRootCause(e);
			if (rootCause != null && rootCause.getMessage() != null) {
				if (rootCause.getMessage().contains("Connection refused")) {
					throw new GradleException("Unable to connect to docker.  Is it running?", e);
				}
			}
			throw e;
		}
	}

	void forceStop(File projectDir) throws Exception {
		try {
			new Impl().doStop(this);
		} catch (Exception e) {
			if (Throwables.getStackTraceAsString(e).contains("Connection refused")) {
				// if we can't connect to docker, then we can't stop it
				// so we'll just ignore the error
			} else {
				e.printStackTrace();
			}
		}
		File kf = keyFile(projectDir);
		if (kf.exists()) {
			java.nio.file.Files.delete(kf.toPath());
		}
	}

	PGSimpleDataSource getConnection() throws IOException {
		String ip;
		int port;
		if (Env.isGitHubAction()) {
			ip = GITHUB_IP;
			port = GITHUB_PORT;
		} else {
			// read the connection properties 
			Properties connectionProps = new Properties();
			try (Reader reader = Files.asCharSource(dockerConnectionParams, StandardCharsets.UTF_8).openBufferedStream()) {
				connectionProps.load(reader);
			} catch (IOException e) {
				throw Errors.asRuntime(e);
			}
			ip = connectionProps.getProperty("host");
			port = Integer.parseInt(connectionProps.getProperty("port"));
		}
		PGSimpleDataSource dataSource = new PGSimpleDataSource();
		dataSource.setServerNames(new String[]{ip});
		dataSource.setPortNumbers(new int[]{port});
		dataSource.setUser("root");
		dataSource.setPassword("password");
		dataSource.setDatabaseName("template1");
		dataSource.setConnectTimeout(20);
		return dataSource;
	}

	private static File keyFile(File projectDir) {
		return new File(projectDir, "build/docker");
	}

	private static final int TRY_SILENTLY_FOR = 10_000;
	private static final int TRY_LOUDLY_UNTIL = 12_000;
	private static final int WAIT_BETWEEN_TRIES = 100;

	public static void keepTrying(Throwing.Runnable toAttempt) {
		long start = System.currentTimeMillis();
		while (true) {
			try {
				toAttempt.run();
				return;
			} catch (Throwable e) {
				long elapsed = System.currentTimeMillis() - start;
				if (elapsed < TRY_SILENTLY_FOR) {
					Errors.rethrow().run(() -> Thread.sleep(WAIT_BETWEEN_TRIES));
				} else if (elapsed < TRY_LOUDLY_UNTIL) {
					e.printStackTrace();
					Errors.rethrow().run(() -> Thread.sleep(WAIT_BETWEEN_TRIES));
				} else {
					throw Errors.asRuntime(e);
				}
			}
		}
	}

	DockerComposeRule rule() {
		return DockerComposeRule.builder()
				.file(dockerComposeFile.getAbsolutePath())
				.projectName(ProjectName.fromString(Integer.toString(Math.abs(dockerComposeFile.getAbsolutePath().hashCode()))))
				.waitingForService("postgres", HealthChecks.toHaveAllPortsOpen())
				.pullOnStartup(dockerPullOnStartup)
				.removeConflictingContainersOnStartup(true)
				.saveLogsTo(new File(buildDir, "tmp/docker").getAbsolutePath())
				.shutdownStrategy(ShutdownStrategy.SKIP)
				.build();
	}

	private static class Impl extends SetupCleanup<SetupCleanupDockerFlyway> {
		@Override
		protected void doStart(SetupCleanupDockerFlyway key) throws IOException, InterruptedException {
			DockerComposeRule rule;
			String ip;
			int port;
			if (Env.isGitHubAction()) {
				// circle provides the container for us
				rule = null;
				ip = GITHUB_IP;
				port = GITHUB_PORT;
			} else {
				// start docker-compose and get postgres from that
				rule = key.rule();
				rule.before();
				Files.createParentDirs(key.dockerConnectionParams);

				DockerPort dockerPort = rule.containers()
						.container("postgres")
						.port(5432);
				ip = dockerPort.getIp();
				port = dockerPort.getExternalPort();
			}
			Files.createParentDirs(key.dockerConnectionParams);
			Files.asCharSink(key.dockerConnectionParams, StandardCharsets.UTF_8).write("host=" + ip + "\nport=" + port);

			// run flyway
			PGSimpleDataSource postgres = key.getConnection();
			keepTrying(() -> {
				Flyway.configure()
						.dataSource(postgres)
						.locations("filesystem:" + key.flywayMigrations.getAbsolutePath())
						.schemas("public")
						.load()
						.migrate();
			});

			// write out the schema to disk
			String schema;
			List<String> pg_dump_args = Arrays.asList("-d", "template1", "-U", postgres.getUser(), "--schema-only", "--restrict-key=reproduciblediff");
			if (rule == null) {
				Process process = Runtime.getRuntime().exec(ImmutableList.<String> builder().add(
						"pg_dump",
						"-h", GITHUB_IP, "-p", Integer.toString(GITHUB_PORT))
						.addAll(pg_dump_args).build().toArray(new String[0]));
				// swallow errors (not great...)
				new InputStreamCollector(process.getErrorStream());
				InputStreamCollector output = new InputStreamCollector(process.getInputStream());
				process.waitFor(10, TimeUnit.SECONDS);
				output.join(1_000);
				schema = new String(output.result().getLeft(), StandardCharsets.UTF_8);
			} else {
				schema = rule.dockerCompose().exec(DockerComposeExecOption.noOptions(),
						"postgres", DockerComposeExecArgument.arguments(ImmutableList.builder().add("pg_dump")
								.addAll(pg_dump_args)
								.build().toArray(new String[0])));
			}
			Files.createParentDirs(key.flywaySchemaDump);
			Files.write(schema, key.flywaySchemaDump, StandardCharsets.UTF_8);
		}

		@Override
		protected void doStop(SetupCleanupDockerFlyway key) throws IOException, InterruptedException {
			if (!Env.isGitHubAction()) {
				DockerCompose compose = key.rule().dockerCompose();
				compose.kill();
				compose.rm();
			}
		}

		static class InputStreamCollector extends Thread {
			private final InputStream iStream;
			private Either<byte[], IOException> result;

			public InputStreamCollector(InputStream is) {
				this.iStream = Objects.requireNonNull(is);
				start();
			}

			@Override
			public synchronized void run() {
				try {
					ByteArrayOutputStream output = new ByteArrayOutputStream();
					ByteStreams.copy(iStream, output);
					result = Either.createLeft(output.toByteArray());
				} catch (IOException ex) {
					result = Either.createRight(ex);
				}
			}

			public synchronized Either<byte[], IOException> result() {
				return result;
			}
		}
	}
}
