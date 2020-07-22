package dev.snowdrop.jira.atlassian.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class Component {
	@JsonProperty
	private Issue issue;

	@JsonProperty
	private List<String> properties;

	@JsonIgnore
	private List<Artifact> artifacts;

	@JsonIgnore
	private Release parent;

	public Release getParent() {
		return parent;
	}

	public void setParent(Release release) {
		this.parent = release;
	}

	public String getName() {
		// TODO: fix me
		final String s = properties.get(0);
		return s.substring(0, 1).toUpperCase() + s.substring(1);
	}

	public String getTitle() {
		return getName() + " compatibility information for Spring Boot " + parent.getVersion();
	}

	public Issue getIssue() {
		return issue;
	}

	public List<Artifact> getArtifacts() {
		this.artifacts = new LinkedList<>();
		final Map<String, List<Artifact>> artifacts = parent.getPOM().getArtifacts();
		for (String property : properties) {
			this.artifacts.addAll(artifacts.getOrDefault(property, Collections.emptyList()));
		}
		return this.artifacts;
	}
}
