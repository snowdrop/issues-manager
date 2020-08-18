package dev.snowdrop.jira.atlassian;

import com.atlassian.jira.rest.client.api.IssueRestClient;
import com.atlassian.jira.rest.client.api.domain.BasicIssue;
import dev.snowdrop.jira.atlassian.model.Release;
import org.jboss.logging.Logger;
import picocli.CommandLine;

import java.util.List;

import static dev.snowdrop.jira.atlassian.ReleaseService.RELEASE_TICKET_TEMPLATE;
import static dev.snowdrop.jira.atlassian.Utility.initRestClient;
import static dev.snowdrop.jira.atlassian.Utility.restClient;

@CommandLine.Command(
		name = "issue-manager", mixinStandardHelpOptions = true, version = "issues-manager 1.0.0"
)
public class Client {
	private static final Logger LOG = Logger.getLogger(Client.class);

	@CommandLine.Option(names = {"-u", "--user"}, description = "JIRA user", required = true, scope = CommandLine.ScopeType.INHERIT)
	private String user;
	@CommandLine.Option(names = {"-p", "--password"}, description = "JIRA password", required = true, scope = CommandLine.ScopeType.INHERIT)
	private String password;
	@CommandLine.Option(names = "--url", description = "URL of the JIRA server", showDefaultValue =
			CommandLine.Help.Visibility.ALWAYS, defaultValue = Utility.JIRA_SERVER, scope = CommandLine.ScopeType.INHERIT)
	private String jiraServerURI;


	public static void main(String[] argv) throws Exception {
		int exitCode = new CommandLine(new Client()).execute(argv);
		System.exit(exitCode);
	}

	private void initClient() {
		initRestClient(jiraServerURI, user, password);
	}

	@CommandLine.Command(name = "get", description = "Retrieve the specified issue")
	public void get(
			@CommandLine.Parameters(description = "JIRA issue key") String key
	) {
		initClient();
		System.out.println(Service.getIssue(key));
	}

	@CommandLine.Command(name = "clone",
			description = "Clone the specified issue using information from the release associated with the specified git reference")
	public void clone(
			@CommandLine.Option(names = {"-g", "--git"},
					description = "Git reference in the <github org>/<github repo>/<branch | tag | hash> format") String gitRef,
			@CommandLine.Parameters(description = "JIRA issue key",
					defaultValue = ReleaseService.RELEASE_TICKET_TEMPLATE,
					showDefaultValue = CommandLine.Help.Visibility.ALWAYS) String toCloneFrom
	) {
		initClient();
		final Release release = Release.createFromGitRef(gitRef);
		System.out.println(ReleaseService.clone(release, toCloneFrom));
	}

	@CommandLine.Command(name = "create-component",
			description = "Create component requests for the release associated with the specified git reference")
	public void createComponentRequests(
			@CommandLine.Option(names = {"-g", "--git"},
					description = "Git reference in the <github org>/<github repo>/<branch | tag | hash> format") String gitRef
	) {
		initClient();
		final Release release = Release.createFromGitRef(gitRef);
		ReleaseService.createComponentRequests(release);
	}

	@CommandLine.Command(name = "delete", description = "Delete the specified comma-separated issues")
	public void delete(
			@CommandLine.Parameters(description = "Comma-separated JIRA issue keys", split = ",") List<String> issues
	) {
		initClient();
		Service.deleteIssues(issues);
	}

	@CommandLine.Command(name = "link",
			description = "Link the issue specified by the 'from' option to the issue specified by the 'to' option")
	public void link(
			@CommandLine.Parameters(description = ("JIRA issue key from which a link should be created")) String fromIssue,
			@CommandLine.Option(names = {"-t", "--to"}, description = ("JIRA issue key to link to"), required = true) String toIssue
	) {
		initClient();
		Service.linkIssue(fromIssue, toIssue);
	}

	@CommandLine.Command(name = "start-release",
			description = "Start the release process for the release associated with the specified git reference")
	public void startRelease(
			@CommandLine.Option(names = {"-g", "--git"},
					description = "Git reference in the <github org>/<github repo>/<branch | tag | hash> format") String gitRef
	) {
		initClient();
		Release release = Release.createFromGitRef(gitRef);

		BasicIssue issue;
		// first check if we already have a release ticket, in which case we don't need to clone the template
		final String releaseTicket = release.getJiraKey();
		if (!Utility.isStringNullOrBlank(releaseTicket)) {
			final IssueRestClient cl = restClient.getIssueClient();
			try {
				issue = cl.getIssue(releaseTicket).claim();
				System.out.printf("Release ticket %s already exists, skipping cloning step", releaseTicket);
			} catch (Exception e) {
				// if we got an exception, assume that it's because we didn't find the ticket
				issue = ReleaseService.clone(release, RELEASE_TICKET_TEMPLATE);
			}
		} else {
			// no release ticket was specified, clone
			issue = ReleaseService.clone(release, RELEASE_TICKET_TEMPLATE);
		}
		ReleaseService.createComponentRequests(release);
		System.out.println(issue);
	}
}
