/**
 * Copyright 2020 Red Hat, Inc. and/or its affiliates.
 *
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package dev.snowdrop.jira.atlassian.model;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * @author <a href="claprun@redhat.com">Christophe Laprun</a>
 */
public class POM {
	private final Model model;

	private POM(Model model) {
		this.model = model;
	}

	public static POM createFrom(InputStream inputStream) {
		try {
			MavenXpp3Reader reader = new MavenXpp3Reader();
			final Model model = reader.read(inputStream);
			return new POM(model);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	public Map<String, List<Artifact>> getArtifacts() {
		final Properties properties = model.getProperties();
		final List<Dependency> dependencies = model.getDependencyManagement().getDependencies();
		final Map<String, List<Artifact>> result = new HashMap<>(dependencies.size());
		for (Map.Entry<Object, Object> prop : properties.entrySet()) {
			final String key = prop.getKey().toString();
			final String version = prop.getValue().toString();
			final List<Artifact> artifacts = dependencies.stream()
					.filter(d -> d.getVersion().contains(key))
					.map(d -> new Artifact(d.getGroupId(), d.getArtifactId(), version))
					.collect(Collectors.toList());
			result.put(key.substring(0, key.indexOf(".version")), artifacts);
		}

		return result;
	}

	public String getVersion() {
		return model.getParent().getVersion();
	}
}
