package dev.snowdrop.release.services;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.MustacheFactory;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

public class Utility {
    private static final DateTimeFormatter dateParser = ISODateTimeFormat.date();
    public static final String JIRA_SERVER = "https://issues.redhat.com/";
    public static final String JIRA_ISSUES_API = "https://issues.redhat.com/rest/api/2/";
    public static final MustacheFactory mf = new DefaultMustacheFactory();
    
    // jackson databind
    public static final YAMLMapper MAPPER = new YAMLMapper();
    
    static {
        MAPPER.disable(MapperFeature.AUTO_DETECT_CREATORS,
            MapperFeature.AUTO_DETECT_FIELDS,
            MapperFeature.AUTO_DETECT_GETTERS,
            MapperFeature.AUTO_DETECT_IS_GETTERS);
        final var factory = MAPPER.getFactory();
        factory.disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER);
        factory.enable(YAMLGenerator.Feature.MINIMIZE_QUOTES);
        MAPPER.setSerializationInclusion(JsonInclude.Include.NON_NULL);
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
