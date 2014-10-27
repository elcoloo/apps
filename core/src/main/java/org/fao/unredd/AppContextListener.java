package org.fao.unredd;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import net.sf.json.JSONObject;
import net.sf.json.JSONSerializer;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.log4j.Logger;
import org.fao.unredd.layers.LayerFactory;
import org.fao.unredd.layers.folder.FolderLayerFactory;
import org.fao.unredd.portal.Config;

public class AppContextListener implements ServletContextListener {

	private static Logger logger = Logger.getLogger(AppContextListener.class);

	@Override
	public void contextInitialized(ServletContextEvent sce) {
		ServletContext servletContext = sce.getServletContext();
		String rootPath = servletContext.getRealPath("/");
		String configInitParameter = servletContext
				.getInitParameter("PORTAL_CONFIG_DIR");
		boolean configCache = Boolean.parseBoolean(System
				.getenv("NFMS_CONFIG_CACHE"));
		Config config = new Config(rootPath, configInitParameter, configCache);
		servletContext.setAttribute("config", config);

	//	String indicatorsFolder = config.getIndicatorsFolder();
		LayerFactory layerFactory;
		try {
			layerFactory = new DBLayerFactory("workspace:newlayer");
			servletContext.setAttribute("layer-factory", layerFactory);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		final ArrayList<String> js = new ArrayList<String>();
		final ArrayList<String> css = new ArrayList<String>();
		ContextEntryListener cssAndJsCollector = new ContextEntryListener() {

			@Override
			public void accept(String path, ContextEntryReader entryReader)
					throws IOException {
				boolean modules = path.startsWith("nfms/modules");
				boolean styles = path.startsWith("nfms/styles");
				File pathFile = new File(path);
				if ((styles || modules) && path.endsWith(".css")) {
					css.add(pathFile.getParentFile().getName() + "/"
							+ pathFile.getName());
				} else if (modules && path.endsWith(".js")) {
					String name = pathFile.getName();
					name = name.substring(0, name.length() - 3);
					js.add(name);
				}
			}

		};
		scanClasses(servletContext, cssAndJsCollector);
		scanJars(servletContext, cssAndJsCollector);
		servletContext.setAttribute("js-paths", js);
		servletContext.setAttribute("css-paths", css);

		final Map<String, String> requirejsPaths = new HashMap<String, String>();
		final Map<String, String> requirejsShims = new HashMap<String, String>();
		ContextEntryListener pluginDescriptorAnalyzer = new ContextEntryListener() {

			@Override
			public void accept(String path, ContextEntryReader entryReader)
					throws IOException {
				if (path.matches("\\Qnfms/\\E\\w+\\Q-conf.json\\E")) {
					JSONObject jsonRoot = (JSONObject) JSONSerializer
							.toJSON(entryReader.getContent());
					if (jsonRoot.has("requirejs")) {
						JSONObject requireJS = jsonRoot
								.getJSONObject("requirejs");
						fill(requirejsPaths,
								(JSONObject) requireJS.get("paths"));
						fill(requirejsShims, (JSONObject) requireJS.get("shim"));
					}
				}
			}

			private void fill(Map<String, String> map, JSONObject jsonMap) {
				if (jsonMap == null) {
					return;
				}

				for (Object key : jsonMap.keySet()) {
					Object value = jsonMap.get(key.toString());
					map.put(key.toString(), value.toString());
				}
			}
		};
		scanClasses(servletContext, pluginDescriptorAnalyzer);
		scanJars(servletContext, pluginDescriptorAnalyzer);
		servletContext.setAttribute("requirejs-paths", requirejsPaths);
		servletContext.setAttribute("requirejs-shims", requirejsShims);

	}

	private void scanClasses(ServletContext servletContext,
			ContextEntryListener contextEntryListener) {
		File rootFolder = new File(
				servletContext.getRealPath("WEB-INF/classes/nfms"));
		if (rootFolder.exists()) {
			Iterator<File> allFiles = FileUtils.iterateFiles(rootFolder,
					relevantExtensions, TrueFileFilter.INSTANCE);

			final File referenceFolder = rootFolder.getParentFile();
			int rootPathLength = referenceFolder.getAbsolutePath().length() + 1;
			while (allFiles.hasNext()) {
				File file = allFiles.next();
				String name = file.getAbsolutePath();
				final String relativePath = name.substring(rootPathLength);
				try {
					ContextEntryReader contentReader = new ContextEntryReader() {

						@Override
						public String getContent() throws IOException {
							InputStream input = new BufferedInputStream(
									new FileInputStream(new File(
											referenceFolder, relativePath)));
							String content = IOUtils.toString(input);
							input.close();

							return content;
						}
					};
					contextEntryListener.accept(relativePath, contentReader);
				} catch (IOException e) {
					logger.info("Cannot analyze file:" + relativePath);
				}
			}
		}
	}

	private void scanJars(ServletContext servletContext,
			ContextEntryListener contextEntryListener) {
		Set<String> libJars = servletContext.getResourcePaths("/WEB-INF/lib");
		for (Object jar : libJars) {
			InputStream jarStream = servletContext.getResourceAsStream(jar
					.toString());
			final ZipInputStream zis = new ZipInputStream(
					new BufferedInputStream(jarStream));
			ZipEntry entry;
			try {
				while ((entry = zis.getNextEntry()) != null) {
					String entryPath = entry.getName();
					if (relevantExtensions.accept(new File(entryPath))) {
						ContextEntryReader contentReader = new ContextEntryReader() {

							@Override
							public String getContent() throws IOException {
								return IOUtils.toString(zis);
							}
						};
						contextEntryListener.accept(entryPath, contentReader);
					}
				}
			} catch (IOException e) {
				throw new RuntimeException("Cannot start the application", e);
			} finally {
				try {
					zis.close();
				} catch (IOException e) {
				}
			}

		}
	}

	@Override
	public void contextDestroyed(ServletContextEvent sce) {
	}

	private interface ContextEntryListener {

		void accept(String path, ContextEntryReader contentReader)
				throws IOException;

	}

	private interface ContextEntryReader {
		String getContent() throws IOException;
	}

	private static IOFileFilter relevantExtensions = new IOFileFilter() {

		@Override
		public boolean accept(File file, String name) {
			return true;
		}

		@Override
		public boolean accept(File file) {
			String lowerCase = file.getName().toLowerCase();
			return lowerCase.endsWith(".js") || lowerCase.endsWith(".css")
					|| lowerCase.endsWith(".json");
		}
	};

}
