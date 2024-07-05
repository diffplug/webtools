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
package com.diffplug.webtools.serve;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.stream.Stream;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.TaskAction;

/** Serves a collection of folders using URLs. */
public class StaticServerTask extends DefaultTask {
	private int port = 8080;

	/** Set the port for this server. */
	public void setPort(int port) {
		this.port = port;
	}

	private File dir;

	public void setDir(File dir) {
		this.dir = dir;
	}

	/** Returns a stream of all of the running, non-virtual, non-loopback addresses. */
	private static Stream<InetAddress> getLocalAddresses() throws SocketException {
		return Collections.list(NetworkInterface.getNetworkInterfaces()).stream()
				.filter(iface -> get(iface::isUp))
				.filter(iface -> !get(iface::isVirtual))
				.filter(iface -> !get(iface::isLoopback))
				.flatMap(iface -> Collections.list(iface.getInetAddresses()).stream()
						.filter(addr -> addr.getAddress().length == 4));
	}

	interface ThrowingGetter<T> {
		T get() throws Exception;
	}

	private static <T> T get(ThrowingGetter<T> getter) {
		try {
			return getter.get();
		} catch (Exception e) {
			if (e instanceof RuntimeException) {
				throw (RuntimeException) e;
			} else {
				throw new RuntimeException(e);
			}
		}
	}

	@TaskAction
	public void start() throws Exception {
		Server server = new Server(port);

		ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
		context.setContextPath("/");
		context.setResourceBase(dir.getAbsolutePath());
		context.addServlet(DefaultServlet.class, "/");
		server.setHandler(context);
		server.start();

		// if there are any alternative IPs, print those too
		getLocalAddresses()
				.forEach(addr -> System.out.println("    Serving at IP: " + addr.getHostAddress() + ":" + port));

		// wait for user input to stop
		getInputChar("Press any key to stop.");
		server.stop();
		server.join();
	}

	/** Returns an input after the given prompt. */
	private static Character getInputChar(String prompt) {
		try {
			flushInput();
			System.out.println(prompt);
			return Character.valueOf((char) System.in.read());
		} catch (IOException e) {
			// escalate any problems (there shouldn't be any) to runtime exceptions
			throw new RuntimeException(e);
		}
	}

	private static void flushInput() throws IOException {
		// flush anything sitting in the buffer
		while (System.in.available() > 0) {
			System.in.read();
		}
	}
}
