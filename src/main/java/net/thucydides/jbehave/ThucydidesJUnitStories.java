package net.thucydides.jbehave;

import ch.lambdaj.Lambda;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.MoreExecutors;
import net.thucydides.core.ThucydidesSystemProperty;
import net.thucydides.core.guice.Injectors;
import net.thucydides.core.util.EnvironmentVariables;
import net.thucydides.core.webdriver.ThucydidesWebDriverSupport;
import net.thucydides.jbehave.runners.ThucydidesReportingRunner;
import org.codehaus.plexus.util.StringUtils;
import org.jbehave.core.ConfigurableEmbedder;
import org.jbehave.core.configuration.Configuration;
import org.jbehave.core.embedder.Embedder;
import org.jbehave.core.io.StoryFinder;
import org.jbehave.core.reporters.Format;
import org.jbehave.core.steps.InjectableStepsFactory;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static net.thucydides.jbehave.ThucydidesJBehaveSystemProperties.IGNORE_FAILURES_IN_STORIES;
import static net.thucydides.jbehave.ThucydidesJBehaveSystemProperties.METAFILTER;
import static net.thucydides.jbehave.ThucydidesJBehaveSystemProperties.STORY_TIMEOUT_IN_SECS;
import static org.jbehave.core.reporters.Format.CONSOLE;
import static org.jbehave.core.reporters.Format.HTML;
import static org.jbehave.core.reporters.Format.XML;

/**
 * A JUnit-runnable test case designed to run a set of ThucydidesWebdriverIntegration-enabled JBehave stories in a given package.
 * By default, it will look for *.story files on the classpath, and steps in or underneath the current package.
 * You can redefine these constraints as follows:
 */
@RunWith(ThucydidesReportingRunner.class)
public class ThucydidesJUnitStories extends ConfigurableEmbedder {

    public static final String DEFAULT_STORY_NAME =  "**/*.story";
    public static final List<String> DEFAULT_GIVEN_STORY_PREFIX = ImmutableList.of("Given","Precondition");
	public static final String SKIP_FILTER = "-skip";
	public static final String IGNORE_FILTER = "-ignore";
	public static final String DEFAULT_METAFILTER = SKIP_FILTER + " " + IGNORE_FILTER;

	private net.thucydides.core.webdriver.Configuration systemConfiguration;
	private EnvironmentVariables environmentVariables;

	private List<String> stories;
	private String storyFolder = "";
    private String storyNamePattern = DEFAULT_STORY_NAME;

	private Embedder embedder;
    private Configuration configuration;
	private InjectableStepsFactory injectableStepsFactory;
	private List<Format> formats = Arrays.asList(CONSOLE, HTML, XML);

	public ThucydidesJUnitStories() {}

    protected ThucydidesJUnitStories(EnvironmentVariables environmentVariables) {
        this.setEnvironmentVariables(environmentVariables.copy());
    }

    protected ThucydidesJUnitStories(net.thucydides.core.webdriver.Configuration configuration) {
        this.setSystemConfiguration(configuration);
    }

	@Override
	@Test
	public void run() {
		try {
			getConfiguredEmbedder().runStoriesAsPaths(storyPaths());
		} finally {
			if (getSystemConfiguration().getUseUniqueBrowser()) {
				ThucydidesWebDriverSupport.closeAllDrivers();
			}
		}
	}

    @Override
    public Configuration configuration() {
        if (configuration == null) {
            net.thucydides.core.webdriver.Configuration thucydidesConfiguration = getSystemConfiguration();
            if (environmentVariables != null) {
                thucydidesConfiguration = thucydidesConfiguration.withEnvironmentVariables(environmentVariables);
            }
            configuration = ThucydidesJBehave.defaultConfiguration(thucydidesConfiguration, formats, this);
        }
        return configuration;
    }

    @Override
    public InjectableStepsFactory stepsFactory() {
	    if (injectableStepsFactory == null) {
            injectableStepsFactory = new ThucydidesStepFactory(configuration(), getRootPackage(), getClassLoader());
	    }
	    return injectableStepsFactory;
    }

	public Embedder getConfiguredEmbedder() {
		if (embedder == null) {
			embedder = configuredEmbedder();
			embedder.embedderControls().doIgnoreFailureInView(true);
			embedder.embedderControls().doIgnoreFailureInStories(getIgnoreFailuresInStories());
			embedder.embedderControls().useStoryTimeoutInSecs(getStoryTimeoutInSecs());
			embedder.useExecutorService(MoreExecutors.sameThreadExecutor());
			if (metaFiltersAreDefined()) {
				embedder.useMetaFilters(getMetaFilters());
			}
		}
		return embedder;
	}

    /**
     * The class loader used to obtain the JBehave and Step implementation classes.
     * You normally don't need to worry about this, but you may need to override it if your application
     * is doing funny business with the class loaders.
     */
    public ClassLoader getClassLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

    public List<String> storyPaths() {
	    if (stories == null) {
	        Set<String> storyPaths = Sets.newHashSet();
	        List<String> pathExpressions = getStoryPathExpressions();
	        StoryFinder storyFinder = new StoryFinder();
	        for(String pathExpression : pathExpressions) {
	            if (absolutePath(pathExpression)) {
	                storyPaths.add(pathExpression);
	            }
	            for(URL classpathRootUrl : allClasspathRoots()) {
	                storyPaths.addAll(storyFinder.findPaths(classpathRootUrl, pathExpression, ""));
	            }
	            storyPaths = pruneGivenStoriesFrom(storyPaths);
	        }
	        stories = Lists.newArrayList(storyPaths);
	    }
        return stories;
    }

    private Set<String> pruneGivenStoriesFrom(Set<String> storyPaths) {
        List<String> filteredPaths = Lists.newArrayList(storyPaths);
        for (String skippedPrecondition : skippedPreconditions()) {
            filteredPaths = removeFrom(filteredPaths)
                            .pathsNotStartingWith(skippedPrecondition)
                            .and().pathsNotStartingWith("/" + skippedPrecondition)
                            .filter();
        }
        return Sets.newHashSet(filteredPaths);
    }

    class FilterBuilder {
        private final List<String> paths;

        public FilterBuilder(List<String> paths) {
            this.paths = paths;
        }

        public FilterBuilder pathsNotStartingWith(String skippedPrecondition) {
            List<String> filteredPaths = Lists.newArrayList();
            for(String path : paths) {
                if (!startsWith(skippedPrecondition, path)) {
                    filteredPaths.add(path);
                }
            }
            return new FilterBuilder(filteredPaths);
        }

        public FilterBuilder and() { return this; }

        public List<String> filter() {
            return ImmutableList.copyOf(paths);
        }
        private boolean startsWith(String skippedPrecondition, String path) {
            return path.toLowerCase().startsWith(skippedPrecondition.toLowerCase());
        }
    }

    private FilterBuilder removeFrom(List<String> filteredPaths) {
        return new FilterBuilder(filteredPaths);
    }

    private List<String> skippedPreconditions() {
        return DEFAULT_GIVEN_STORY_PREFIX;
    }

    private boolean absolutePath(String pathExpression) {
        return (!pathExpression.contains("*"));
    }

    private List<URL> allClasspathRoots() {
        try {
            return Collections.list(getClassLoader().getResources("."));
        } catch (IOException e) {
            throw new IllegalArgumentException("Could not load the classpath roots when looking for story files",e);
        }
    }

    /**
     * The root package on the classpath containing the JBehave stories to be run.
     */
    protected String getRootPackage() {
        return this.getClass().getPackage().getName();
    }

    protected List<String> getStoryPathExpressions() {
        return Lists.newArrayList(Splitter.on(';').trimResults().omitEmptyStrings().split(getStoryPath()));
    }

    /**
     * The root package on the classpath containing the JBehave stories to be run.
     */
    protected String getStoryPath() {
        return (StringUtils.isEmpty(storyFolder)) ? storyNamePattern : storyFolder + "/" + storyNamePattern;
    }

    /**
     * Define the folder on the class path where the stories should be found
     */
    public void findStoriesIn(String storyFolder) {
        this.storyFolder = storyFolder;
    }

    public void useFormats(Format... formats) {
        this.formats = Arrays.asList(formats);
    }

    public void findStoriesCalled(String storyNames) {
        Set<String>  storyPathElements = new StoryPathFinder(getEnvironmentVariables(), storyNames).findAllElements();
        storyNamePattern = Lambda.join(storyPathElements,";");

    }

    /**
     * Use this to override the default ThucydidesWebdriverIntegration configuration - for testing purposes only.
     */
    protected void setSystemConfiguration(net.thucydides.core.webdriver.Configuration systemConfiguration) {
        this.systemConfiguration = systemConfiguration;
    }

    protected void setEnvironmentVariables(EnvironmentVariables environmentVariables) {
        this.environmentVariables = environmentVariables;
    }

    public net.thucydides.core.webdriver.Configuration getSystemConfiguration() {
        if (systemConfiguration == null) {
            systemConfiguration = Injectors.getInjector().getInstance(net.thucydides.core.webdriver.Configuration.class);
        }
        return systemConfiguration;
    }

    protected void useDriver(String driver) {
        getSystemConfiguration().setIfUndefined(ThucydidesSystemProperty.DRIVER.getPropertyName(), driver);
    }

    protected void useUniqueSession() {
        getSystemConfiguration().setIfUndefined(ThucydidesSystemProperty.UNIQUE_BROWSER.getPropertyName(), "true");
    }

	protected EnvironmentVariables getEnvironmentVariables() {
		if (environmentVariables == null) {
			environmentVariables = Injectors.getInjector().getInstance(EnvironmentVariables.class).copy();
		}
		return environmentVariables;
	}

	//////////////////

	private boolean metaFiltersAreDefined() {
		String metaFilters = getMetafilterSetting();
		return !StringUtils.isEmpty(metaFilters);
	}

	private String getMetafilterSetting() {
		String metaFilters = getEnvironmentVariables().getProperty(METAFILTER.getName(), DEFAULT_METAFILTER);
		if (!metaFilters.contains(SKIP_FILTER)) {
			metaFilters = metaFilters + SKIP_FILTER;
		}
		if (!metaFilters.contains(IGNORE_FILTER)) {
			metaFilters = metaFilters + IGNORE_FILTER;
		}
		return metaFilters;
	}

	protected boolean getIgnoreFailuresInStories() {
		return getEnvironmentVariables().getPropertyAsBoolean(IGNORE_FAILURES_IN_STORIES.getName(), true);
	}

	protected int getStoryTimeoutInSecs() {
		return getEnvironmentVariables().getPropertyAsInteger(STORY_TIMEOUT_IN_SECS.getName(), 300);
	}

	protected List<String> getMetaFilters() {
		String metaFilters = getMetafilterSetting();
		return Lists.newArrayList(Splitter.on(",").trimResults().split(metaFilters));
	}

    public ThucydidesConfigurationBuilder runThucydides() {
        return new ThucydidesConfigurationBuilder(this);
    }

    public class ThucydidesConfigurationBuilder {
        private final ThucydidesJUnitStories thucydidesJUnitStories;

        public ThucydidesConfigurationBuilder(ThucydidesJUnitStories thucydidesJUnitStories) {
            this.thucydidesJUnitStories = thucydidesJUnitStories;
        }

        public ThucydidesConfigurationBuilder withDriver(String driver) {
            useDriver(driver);
            return this;
        }

        public ThucydidesPropertySetter withProperty(ThucydidesSystemProperty property) {
            return new ThucydidesPropertySetter(thucydidesJUnitStories, property);
        }

        public ThucydidesPropertySetter withProperty(String property) {
            return new ThucydidesPropertySetter(thucydidesJUnitStories, property);
        }

        public ThucydidesConfigurationBuilder inASingleSession() {
            useUniqueSession();
            return this;
        }
    }

    public class ThucydidesPropertySetter {
        private final ThucydidesJUnitStories thucydidesJUnitStories;
        private final String propertyToSet;

        public ThucydidesPropertySetter(ThucydidesJUnitStories thucydidesJUnitStories,
                                        ThucydidesSystemProperty propertyToSet) {
            this.thucydidesJUnitStories = thucydidesJUnitStories;
            this.propertyToSet = propertyToSet.getPropertyName();
        }

        public ThucydidesPropertySetter(ThucydidesJUnitStories thucydidesJUnitStories,
                                        String propertyToSet) {
            this.thucydidesJUnitStories = thucydidesJUnitStories;
            this.propertyToSet = propertyToSet;
        }

        public void setTo(boolean value) {
            thucydidesJUnitStories.getSystemConfiguration().setIfUndefined(propertyToSet,
                    Boolean.toString(value));
        }

        public void setTo(String value) {
            thucydidesJUnitStories.getSystemConfiguration().setIfUndefined(propertyToSet, value);
        }

        public void setTo(Integer value) {
            thucydidesJUnitStories.getSystemConfiguration().setIfUndefined(propertyToSet,
                    Integer.toString(value));
        }
    }

}
