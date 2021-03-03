package dev.snowdrop.release;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import com.atlassian.jira.rest.client.api.JiraRestClient;
import com.atlassian.jira.rest.client.api.domain.BasicIssue;
import dev.snowdrop.release.model.Issue;
import dev.snowdrop.release.model.Release;
import dev.snowdrop.release.reporting.ReportingService;
import dev.snowdrop.release.services.CVEService;
import dev.snowdrop.release.services.GitService;
import dev.snowdrop.release.services.IssueService;
import dev.snowdrop.release.services.ReleaseFactory;
import dev.snowdrop.release.services.Utility;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import org.jboss.logging.Logger;
import picocli.CommandLine;

@CommandLine.Command(name = "issue-manager", mixinStandardHelpOptions = true, version = "issues-manager 1.0.0")
@ApplicationScoped
@QuarkusMain
public class App implements QuarkusApplication {
    public final static String CVE_REPORT_REPO_NAME = "snowdrop/reports";

    static final Logger LOG = Logger.getLogger(App.class);

    @CommandLine.Option(
            names = { "-u", "--user" },
            description = "JIRA user",
            required = true,
            scope = CommandLine.ScopeType.INHERIT)
    private String user;
    @CommandLine.Option(
            names = { "-p", "--password" },
            description = "JIRA password",
            required = true,
            scope = CommandLine.ScopeType.INHERIT)
    private String password;
    @CommandLine.Option(
            names = "--url",
            description = "URL of the JIRA server",
            showDefaultValue = CommandLine.Help.Visibility.ALWAYS,
            defaultValue = Utility.JIRA_SERVER,
            scope = CommandLine.ScopeType.INHERIT)
    private String jiraServerURI;
    @CommandLine.Option(
            names = { "-w", "--watchers" },
            description = "Comma-separated list of user names to add to watchers",
            scope = CommandLine.ScopeType.INHERIT,
            split = ",")
    private List<String> watchers;

    @Inject
    ReleaseFactory factory;

    @Inject
    IssueService service;

    @Inject
    CommandLine.IFactory cliFactory;

    @Inject
    GitService git;

    @Inject
    CVEService cveService;

    @Inject
    JiraRestClient client;

    @Inject
    ReportingService reportingService;

    public static void main(String[] argv) throws Exception {
        Quarkus.run(App.class, argv);
    }

    @Override
    public int run(String... args) throws Exception {
        int exitCode = new CommandLine(this, cliFactory).execute(args);
        return exitCode;
    }

    @CommandLine.Command(name = "get", description = "Retrieve the specified issue")
    public void get(@CommandLine.Parameters(description = "JIRA issue key") String key) {
        System.out.println(service.getIssue(key));
    }

    @CommandLine.Command(
            name = "clone",
            description = "Clone the specified issue using information from the release associated with the specified git reference")
    public void clone(
            @CommandLine.Option(
                    names = { "-g", "--git" },
                    description = "Git reference in the <github org>/<github repo>/<branch | tag | hash> format") String gitRef,
            @CommandLine.Parameters(
                    description = "JIRA issue key",
                    defaultValue = IssueService.RELEASE_TICKET_TEMPLATE,
                    showDefaultValue = CommandLine.Help.Visibility.ALWAYS) String toCloneFrom) throws Throwable {
        final Release release = factory.createFromGitRef(gitRef);
        System.out.println(service.clone(release, toCloneFrom, watchers));
    }

    @CommandLine.Command(
            name = "create-component",
            description = "Create component requests for the release associated with the specified git reference")
    public void createComponentRequests(
            @CommandLine.Option(
                    names = { "-g", "--git" },
                    description = "Git reference in the <github org>/<github repo>/<branch | tag | hash> format") String gitRef)
            throws Throwable {
        final Release release = factory.createFromGitRef(gitRef);
        service.createComponentRequests(release, watchers);
    }

    @CommandLine.Command(name = "delete", description = "Delete the specified comma-separated issues")
    public void delete(
            @CommandLine.Parameters(description = "Comma-separated JIRA issue keys", split = ",") List<String> issues) {
        service.deleteIssues(issues);
    }

    @CommandLine.Command(
            name = "link",
            description = "Link the issue specified by the 'from' option to the issue specified by the 'to' option")
    public void link(
            @CommandLine.Parameters(
                    description = ("JIRA issue key from which a link should be created")) String fromIssue,
            @CommandLine.Option(
                    names = { "-t", "--to" },
                    description = ("JIRA issue key to link to"),
                    required = true) String toIssue) {
        service.linkIssue(fromIssue, toIssue);
    }

    @CommandLine.Command(
            name = "start-release",
            description = "Start the release process for the release associated with the specified git reference")
    public void startRelease(
            @CommandLine.Option(
                    names = { "-g", "--git" },
                    description = "Git reference in the <github org>/<github repo>/<branch> format",
                    required = true) String gitRef,
            @CommandLine.Option(
                    names = { "-s", "--skip" },
                    description = "Skip product requests") boolean skipProductRequests,
            @CommandLine.Option(
                    names = { "-t", "--test" },
                    description = "Create a test release ticket using the SB project for all requests") boolean test,
            @CommandLine.Option(
                    names = { "-o", "--token" },
                    description = "Github API token",
                    required = true) String token,
            @CommandLine.Option(
                    names = { "-r", "--release-date" },
                    description = "Release Date(yyyy-mm-dd)",
                    required = true) String releaseDate,
            @CommandLine.Option(
                    names = { "-e", "--eol-date" },
                    description = "End of Life Date(yyyy-mm-dd)",
                    required = true) String eolDate) throws Throwable {
        if (!test) {
            git.initRepository(gitRef, token); // init git repository to be able to update release
        }

        Release release = factory.createFromGitRef(gitRef, skipProductRequests, true);
        release.setTest(test);

        try {
            release.setSchedule(releaseDate, eolDate);
        } catch (Exception e) {
            LOG.error("Invalid release date", e);
            return;
        }

        List<String> errors = release.validateSchedule();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(errors.stream().reduce("Invalid release:\n", Utility.errorsFormatter(
                    0)));
        }

        BasicIssue issue;
        // first check if we already have a release ticket, in which case we don't need
        // to clone the template
        final String releaseTicket = release.getJiraKey();
        if (!Utility.isStringNullOrBlank(releaseTicket)) {
            try {
                issue = service.getIssue(releaseTicket);
                LOG.infof("Release ticket %s already exists, skipping cloning step", releaseTicket);
            } catch (Exception e) {
                // if we got an exception, assume that it's because we didn't find the ticket
                issue = clone(release, token);
            }
        } else {
            // no release ticket was specified, clone
            issue = clone(release, token);

        }

        // link CVEs
        for (var cve : cveService.listCVEs(Optional.of(release.getVersion()), false)) {
            service.linkIssue(issue.getKey(), cve.getKey());
        }

        if (!skipProductRequests) {
            service.createComponentRequests(release, watchers);
        }

        factory.pushChanges(release);
        System.out.println(issue);
    }

    @CommandLine.Command(
            name = "list-cves",
            description = "List CVEs for the specified release or, if not specified, unresolved CVEs")
    public void listCVEs(
            @CommandLine.Option(
                    names = { "-g", "--publish" },
                    description = "Publish the CVE list to github") boolean release,
            @CommandLine.Option(
                    names = { "-o", "--token" },
                    description = "Github API token. Required if --publish is enabled") String token,
            @CommandLine.Parameters(
                    description = "Release for which to retrieve the CVEs, e.g. 2.2.10",
                    arity = "0..1") String version) throws Throwable {
        final var cves = cveService.listCVEs(Optional.ofNullable(version), true);
        System.out.println(reportingService.buildAsciiReport(cves));
        if (release) {
            if (Optional.ofNullable(token).isPresent()) {
                git.closeOldCveIssues("cve", token, CVE_REPORT_REPO_NAME, version);
                String mdReport = reportingService.buildMdReport(cves, git.getCveIssueTitle(version));
                git.createGithubIssue(mdReport, git.getCveIssueTitle(version), "cve", token, CVE_REPORT_REPO_NAME);
            } else {
                LOG.error(
                        "Cannot release CVE to GitHub. Github API token is required if --publish is enabled. Please specify the github token using --token.");
            }
        }
    }

    @CommandLine.Command(name = "status", description = "Compute the release status")
    public void status(
            @CommandLine.Option(
                    names = { "-g", "--git" },
                    description = "Git reference in the <github org>/<github repo>/<branch> format") String gitRef)
            throws Throwable {
        Release release = factory.createFromGitRef(gitRef, false, false);
        final var blocked = new LinkedList<Issue>();
        final var cves = cveService.listCVEs(Optional.ofNullable(release.getVersion()), true);
        release.computeStatus();
        blocked.addAll(cves);
        blocked.addAll(release.getBlocked());
        System.out.println(release.getBlockedNumber() + " blocked issues out of " + release.getConsideredNumber()
                + " considered");
        System.out.println(reportingService.buildAsciiReport(blocked));
    }

    private BasicIssue clone(Release release, String token) throws IOException {
        final var issue = service.clone(release, IssueService.RELEASE_TICKET_TEMPLATE, watchers);
        release.setJiraKey(issue.getKey());
        return issue;
    }
}
