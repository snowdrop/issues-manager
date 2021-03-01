package dev.snowdrop.release.model;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import static dev.snowdrop.release.services.Utility.isStringNullOrBlank;

public class Release extends Issue {
    public static final String RELEASE_SUFFIX = ".RELEASE";
    private static final DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
    @JsonProperty
    private String version;
    @JsonProperty
    private Schedule schedule;
    @JsonProperty
    private List<Component> components;
    @JsonIgnore
    private String gitRef;
    @JsonIgnore
    private POM pom;

    public String getProjectKey() {
        return getProject();
    }

    public String getLongVersionName() {
        return "[Spring Boot " + getVersion() + "] Release steps CR [" + schedule.getFormattedReleaseDate() + "]";
    }

    public String getJiraKey() {
        return getKey();
    }

    /**
     * Associates this Release to the ticket identified by the specified key. Note
     * that if the JIRA key is already set for this Release, this method won't do
     * anything because the source of truth is assumed to be the YAML file.
     *
     * @param key the ticket identifier to which this Release should be associated
     */
    public void setJiraKey(String key) {
        setKey(key);
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public List<Component> getComponents() {
        if (components != null) {
            // make sure that the parent is properly set, could probably be optimized if
            // needed
            components.forEach(c -> c.setParent(this));
            return components;
        }
        return Collections.emptyList();
    }

    public Schedule getSchedule() {
        return schedule;
    }

    public String getGitRef() {
        return gitRef;
    }

    public void setGitRef(String gitRef) {
        this.gitRef = gitRef;
    }

    public POM getPOM() {
        return pom;
    }

    public void setPom(POM pom) {
        this.pom = pom;
    }

    /**
     * Changes the release definition to use the {@link Issue#TEST_JIRA_PROJECT}
     * project for all requests instead of the specified ones so that we can check a
     * test release without spamming projects.
     *
     * @param test whether or not the release should be set to test mode
     */
    public void setTest(boolean test) {
        if (test) {
            setKey(Issue.TEST_ISSUE_KEY); // to avoid the cloning process
            useTestMode();
            components.forEach(c -> {
                var issue = c.getJira();
                if (issue != null) {
                    issue.useTestMode();
                }
                issue = c.getProductIssue();
                if (issue != null) {
                    issue.useTestMode();
                }
            });
        }
    }

    public void setSchedule(String releaseDate, String eolDate) throws ParseException {
        this.schedule = generateSchedule(releaseDate, eolDate);
    }

    public Schedule generateSchedule(String releaseDate, String eolDate) throws ParseException {
        Schedule schedule = new Schedule();
        schedule.setRelease(releaseDate);
        schedule.setDue(getDueDate(releaseDate));
        schedule.setEol(eolDate);
        return schedule;
    }

    public String getDueDate(String releaseDate) throws ParseException {
        Calendar calendar = Calendar.getInstance();
        Date release = dateFormat.parse(releaseDate);
        calendar.setTime(release);
        // Due date is always one month less than the release date
        calendar.add(Calendar.MONTH, -1);
        return dateFormat.format(calendar.getTime());
    }

    public List<String> validate(boolean skipProductRequests, boolean skipScheduleValidation) {
        final List<String> errors = new LinkedList<>();

        // validate version
        if (isStringNullOrBlank(version)) {
            errors.add("missing version");
        } else {
            if (pom == null) {
                errors.add("no associated POM");
            } else {
                final int suffix = version.indexOf(RELEASE_SUFFIX);
                final int len = suffix > 0 ? version.length() - suffix : version.length();
                final var expectedVersion = pom.getVersion();
                if (!this.version.regionMatches(0, expectedVersion, 0, len)) {
                    errors.add(String.format("'%s' release version doesn't match '%s' version in associated POM",
                            this.version, expectedVersion));
                }
            }
        }

        // validate schedule
        if (!skipScheduleValidation) {
            errors.addAll(validateSchedule());
        }

        // validate components
        if (!skipProductRequests) {
            final var components = getComponents();
            components.parallelStream().forEach(c -> errors.addAll(c.validate(getRestClient())));
        }

        return errors;
    }

    public List<String> validateSchedule() {
        final List<String> errors = new LinkedList<>();
        if (schedule == null) {
            errors.add("missing schedule");
        } else {
            if (isStringNullOrBlank(schedule.getReleaseDate())) {
                errors.add("missing release date");
            }
            try {
                schedule.getFormattedReleaseDate();
            } catch (Exception e) {
                errors.add("invalid release ISO8601 date: " + e.getMessage());
            }

            if (isStringNullOrBlank(schedule.getEOLDate())) {
                errors.add("missing EOL date");
            }
            try {
                schedule.getFormattedEOLDate();
            } catch (Exception e) {
                errors.add("invalid EOL ISO8601 date: " + e.getMessage());
            }
        }
        return errors;
    }

    @Override
    protected boolean useExtendedStatus() {
        return true;
    }
}
