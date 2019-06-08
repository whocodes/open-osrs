/*
 * Copyright (c) 2016-2017, Adam <Adam@sigterm.info>
 * Copyright (c) 2018, Tomas Slusny <slusnucky@gmail.com>
 * Copyright (c) 2018 Abex
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.rs;

import com.google.common.io.ByteStreams;
import io.sigpipe.jbsdiff.Diff;
import io.sigpipe.jbsdiff.InvalidHeaderException;
import io.sigpipe.jbsdiff.Patch;
import java.applet.Applet;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.channels.ReadableByteChannel;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import static net.runelite.client.RuneLite.RUNELITE_DIR;
import static net.runelite.client.rs.ClientUpdateCheckMode.AUTO;
import static net.runelite.client.rs.ClientUpdateCheckMode.CUSTOM;
import static net.runelite.client.rs.ClientUpdateCheckMode.NONE;
import static net.runelite.client.rs.ClientUpdateCheckMode.PATCH;
import net.runelite.http.api.RuneLiteAPI;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.compress.compressors.CompressorException;

@Slf4j
@Singleton
public class ClientLoader
{
	private static final File CUSTOMFILE = new File("./injected-client/target/injected-client-1.5.27-SNAPSHOT.jar");
	private static final File PATCHFILE = new File("replace me!");
	private static final File OUTPUT = new File("replace me!");
	private final ClientConfigLoader clientConfigLoader;
	private ClientUpdateCheckMode updateCheckMode;

	@Inject
	private ClientLoader(
		@Named("updateCheckMode") final ClientUpdateCheckMode updateCheckMode,
		final ClientConfigLoader clientConfigLoader)
	{
		this.updateCheckMode = updateCheckMode;
		this.clientConfigLoader = clientConfigLoader;
	}

	public Applet load()
	{
		if (updateCheckMode == NONE)
		{
			return null;
		}

		try
		{
			Manifest manifest = new Manifest();
			manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
			RSConfig config = clientConfigLoader.fetch();

			Map<String, byte[]> zipFile = new HashMap<>();
			{
				Certificate[] jagexCertificateChain = getJagexCertificateChain();
				String codebase = config.getCodeBase();
				String initialJar = config.getInitialJar();
				URL url = new URL(codebase + initialJar);
				Request request = new Request.Builder()
					.url(url)
					.build();

				try (Response response = RuneLiteAPI.CLIENT.newCall(request).execute())
				{
					JarInputStream jis;

					jis = new JarInputStream(response.body().byteStream());
					byte[] tmp = new byte[4096];
					ByteArrayOutputStream buffer = new ByteArrayOutputStream(756 * 1024);
					for (; ; )
					{
						JarEntry metadata = jis.getNextJarEntry();
						if (metadata == null)
						{
							break;
						}

						buffer.reset();
						for (; ; )
						{
							int n = jis.read(tmp);
							if (n <= -1)
							{
								break;
							}
							buffer.write(tmp, 0, n);
						}

						if (!Arrays.equals(metadata.getCertificates(), jagexCertificateChain))
						{
							if (metadata.getName().startsWith("META-INF/"))
							{
								// META-INF/JAGEXLTD.SF and META-INF/JAGEXLTD.RSA are not signed, but we don't need
								// anything in META-INF anyway.
								continue;
							}
							else
							{
								throw new VerificationException("Unable to verify jar entry: " + metadata.getName());
							}
						}

						zipFile.put(metadata.getName(), buffer.toByteArray());
					}
				}
			}

			if (updateCheckMode == PATCH)
			{
				log.debug("Creating patches");
				int patchCount = 0;

				Map<String, byte[]> injectedFile = new HashMap<>();

				loadJar(injectedFile, CUSTOMFILE);

				ByteArrayOutputStream patchOs = new ByteArrayOutputStream(756 * 1024);
				Map<String, byte[]> patchJar = new HashMap<>();

				for (Map.Entry<String, byte[]> file : zipFile.entrySet())
				{
					byte[] gamepackBytes = file.getValue();
					byte[] injectedBytes = injectedFile.get(file.getKey());
					byte[] patchBytes;

					if (Arrays.equals(gamepackBytes, injectedBytes))
					{
						continue;
					}

					Diff.diff(gamepackBytes, injectedBytes, patchOs);
					patchBytes = patchOs.toByteArray();
					String patchName = file.getKey() + ".bs";

					patchJar.put(patchName, patchBytes);
					patchCount++;

					patchOs.reset();
				}

				log.debug("Created patch files for {} files", patchCount);
				saveJar(patchJar, PATCHFILE);

				System.exit(0);
			}

			if (updateCheckMode == AUTO)
			{
				ByteArrayOutputStream patchOs = new ByteArrayOutputStream(756 * 1024);
				int patchCount = 0;

				for (Map.Entry<String, byte[]> file : zipFile.entrySet())
				{
					byte[] bytes;
					try (InputStream is = ClientLoader.class.getResourceAsStream("/patch/" + file.getKey() + ".bs"))
					{
						if (is == null)
						{
							continue;
						}

						bytes = ByteStreams.toByteArray(is);
					}

					patchOs.reset();
					Patch.patch(file.getValue(), bytes, patchOs);
					file.setValue(patchOs.toByteArray());

					++patchCount;
				}

				log.info("Patched {} classes", patchCount);
			}

			if (updateCheckMode == CUSTOM)
			{
				loadJar(zipFile, CUSTOMFILE);
//TODO: Change this				URL url = new URL("https://raw.githubusercontent.com/runelite-extended/maven-repo/master/artifacts/injected-client.jar");
//TODO: Change this				ReadableByteChannel readableByteChannel = Channels.newChannel(url.openStream());
//TODO: Change this				File INJECTED_CLIENT = new File(RUNELITE_DIR+"/injected-client.jar");
//TODO: Change this				INJECTED_CLIENT.mkdirs();
//TODO: Change this				if (INJECTED_CLIENT.exists())
// {
//TODO: Change this					if (getFileSize(INJECTED_CLIENT.toURI().toURL())!= getFileSize(url))
// {
//TODO: Change this						INJECTED_CLIENT.delete();
//TODO: Change this						INJECTED_CLIENT.createNewFile();
//TODO: Change this						System.out.println("Updating Injected Client");
//TODO: Change this						updateInjectedClient(readableByteChannel);
//TODO: Change this					}
//TODO: Change this				} else {
//TODO: Change this					INJECTED_CLIENT.createNewFile();
//TODO: Change this					System.out.println("Initializing Inject Client");
//TODO: Change this					updateInjectedClient(readableByteChannel);
//TODO: Change this				}
//TODO: Change this
//TODO: Change this				JarInputStream fis = new JarInputStream(new FileInputStream(INJECTED_CLIENT));
//TODO: Change this				byte[] tmp = new byte[4096];
//TODO: Change this				ByteArrayOutputStream buffer = new ByteArrayOutputStream(756 * 1024);
//TODO: Change this				for (; ; )
//TODO: Change this				{
//TODO: Change this					JarEntry metadata = fis.getNextJarEntry();
//TODO: Change this					if (metadata == null)
//TODO: Change this					{
//TODO: Change this						break;
//TODO: Change this					}
//TODO: Change this
//TODO: Change this					buffer.reset();
//TODO: Change this					for (; ; )
//TODO: Change this					{
//TODO: Change this						int n = fis.read(tmp);
//TODO: Change this						if (n <= -1)
//TODO: Change this						{
//TODO: Change this							break;
//TODO: Change this						}
//TODO: Change this						buffer.write(tmp, 0, n);
//TODO: Change this					}
//TODO: Change this					zipFile.replace(metadata.getName(), buffer.toByteArray());
//				}
			}

			String initialClass = config.getInitialClass();

			ClassLoader rsClassLoader = new ClassLoader(ClientLoader.class.getClassLoader())
			{
				@Override
				protected Class<?> findClass(String name) throws ClassNotFoundException
				{
					String path = name.replace('.', '/').concat(".class");
					byte[] data = zipFile.get(path);
					if (data == null)
					{
						throw new ClassNotFoundException(name);
					}

					return defineClass(name, data, 0, data.length);
				}
			};

			Class<?> clientClass = rsClassLoader.loadClass(initialClass);

			Applet rs = (Applet) clientClass.newInstance();
			rs.setStub(new RSAppletStub(config));

			return rs;
		}
		catch (IOException | ClassNotFoundException | InstantiationException | IllegalAccessException | SecurityException | VerificationException | CertificateException | CompressorException | InvalidHeaderException e)
		{
			if (e instanceof ClassNotFoundException)
			{
				log.error("Unable to load client - class not found. This means you"
					+ " are not running RuneLite with Maven as the client patch"
					+ " is not in your classpath.");
			}

			log.error("Error loading RS!", e);
			return null;
		}
	}

	private static int getFileSize(URL url)
	{
		URLConnection conn = null;
		try
		{
			conn = url.openConnection();
			if (conn instanceof HttpURLConnection)
			{
				((HttpURLConnection)conn).setRequestMethod("HEAD");
			}
			conn.getInputStream();
			return conn.getContentLength();
		}
		catch (IOException e)
		{
			throw new RuntimeException(e);
		}
		finally
		{
			if (conn instanceof HttpURLConnection)
			{
				((HttpURLConnection)conn).disconnect();
			}
		}
	}

	private void updateInjectedClient(ReadableByteChannel readableByteChannel)
	{
		File INJECTED_CLIENT = new File(RUNELITE_DIR, "injected-client.jar");
		FileOutputStream fileOutputStream;
		try
		{
			fileOutputStream = new FileOutputStream(INJECTED_CLIENT);
			fileOutputStream.getChannel()
					.transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
		}
		catch (IOException e)
		{
			e.printStackTrace();
		}
	}

	private static Certificate[] getJagexCertificateChain() throws CertificateException
	{
		CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
		Collection<? extends Certificate> certificates = certificateFactory.generateCertificates(ClientLoader.class.getResourceAsStream("jagex.crt"));
		return certificates.toArray(new Certificate[0]);
	}

	private static void saveJar(Map<String, byte[]> fileMap, File toFile) throws IOException
	{
		try (JarOutputStream jout = new JarOutputStream(new FileOutputStream(toFile), new Manifest()))
		{
			for (Map.Entry<String, byte[]> entry : fileMap.entrySet())
			{
				JarEntry e = new JarEntry(entry.getKey());
				jout.putNextEntry(e);

				byte[] data = entry.getValue();

				jout.write(data);
				jout.closeEntry();
			}
		}
	}

	private static void loadJar(Map<String, byte[]> toMap, File fromFile) throws IOException
	{
		JarInputStream fis = new JarInputStream(new FileInputStream(fromFile));
		byte[] tmp = new byte[4096];
		ByteArrayOutputStream buffer = new ByteArrayOutputStream(756 * 1024);
		for (; ; )
		{
			JarEntry metadata = fis.getNextJarEntry();
			if (metadata == null)
			{
				break;
			}

			buffer.reset();
			for (; ; )
			{
				int n = fis.read(tmp);
				if (n <= -1)
				{
					break;
				}
				buffer.write(tmp, 0, n);
			}
			toMap.put(metadata.getName(), buffer.toByteArray());
		}
	}
}
