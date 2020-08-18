package dev.snowdrop.jira.atlassian;

import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.domain.Version;
import com.atlassian.jira.rest.client.internal.async.AsynchronousJiraRestClientFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.MustacheFactory;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class Utility {
	private static final DateTimeFormatter dateParser = ISODateTimeFormat.date();
	public static final String JIRA_SERVER = "https://issues.redhat.com/";
	public static final String JIRA_ISSUES_API = "https://issues.redhat.com/rest/api/2/";
	public static JiraRestClient restClient;
	public static final MustacheFactory mf = new DefaultMustacheFactory();

	// jackson databind
	public static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

	public static void initRestClient(String jiraServerUri, String user, String password) {
		AsynchronousJiraRestClientFactory factory = new AsynchronousJiraRestClientFactory();
		restClient = factory.createWithBasicHttpAuthentication(jiraServerUri(jiraServerUri), user, password);
	}

	public static URI jiraServerUri(String uri) {
		return URI.create(Objects.requireNonNullElse(uri, "https://issues.redhat.com/"));
	}

	public static Iterable<Version> setFixVersion() {
		List<Version> versions = new ArrayList<Version>();
		// TODO: Add a method able to fetch the versions and match the one passed within the Release
		Version version = null;
		try {
			version = new Version(
					new URI(JIRA_ISSUES_API + "/version/12345960"),
					12345960L,
					"2.3.0.GA",
					"2.3.0.GA",
					false,
					false,
					null);
		} catch (URISyntaxException e) {
			e.printStackTrace();
		}
		versions.add(version);
		return versions;
	}

	public static Version setTargetRelease() {
		try {
			return new Version(
					new URI(JIRA_ISSUES_API + "/version/12345960"),
					12345960L,
					"2.3.0.GA",
					"Spring Boot 2.3 Release",
					false,
					false,
					null);
		} catch (URISyntaxException e) {
			e.printStackTrace();
		}
		return null;
	}

	public static DateTime toDateTime(String dateTimeSt) {
		return dateParser.parseDateTime(dateTimeSt);
	}

	public static String getFormatted(String dateTimeSt) {
		final DateTime jodaDate = toDateTime(dateTimeSt);
		return jodaDate.toString("dd MMM YYYY");
	}

	public static String getURLFor(String issueKey) {
		return JIRA_SERVER + "browse/" + issueKey;
	}

	public static boolean isStringNullOrBlank(String s) {
		return s == null || s.isBlank();
	}
}
