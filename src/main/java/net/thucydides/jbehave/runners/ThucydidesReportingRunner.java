package net.thucydides.jbehave.runners;

import de.codecentric.jbehave.junit.monitoring.JUnitDescriptionGenerator;
import de.codecentric.jbehave.junit.monitoring.JUnitScenarioReporter;
import net.thucydides.jbehave.ThucydidesJUnitStories;
import org.jbehave.core.ConfigurableEmbedder;
import org.jbehave.core.embedder.StoryRunner;
import org.jbehave.core.model.Story;
import org.jbehave.core.reporters.StoryReporterBuilder;
import org.junit.runner.Description;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.InitializationError;

import java.util.ArrayList;
import java.util.List;

public class ThucydidesReportingRunner extends BlockJUnit4ClassRunner {

	private Description description;
	private int testCount = 0;
	private final ConfigurableEmbedder configurableEmbedder;
	private JUnitDescriptionGenerator descriptionGenerator;

	public ThucydidesReportingRunner(Class<? extends ConfigurableEmbedder> clazz) throws InitializationError {
		super(clazz);
		try {
			configurableEmbedder = clazz.newInstance();
		} catch (Exception e) {
			throw new InitializationError(e);
		}
	}

	@Override
	public void run(RunNotifier notifier) {
		JUnitScenarioReporter junitReporter = new JUnitScenarioReporter(notifier, testCount(), getDescription());
		// tell the reporter how to handle pending steps
		junitReporter.usePendingStepStrategy(configurableEmbedder.configuration().pendingStepStrategy());
		addToStoryReporterFormats(junitReporter);
		super.run(notifier);
	}

	@Override
	public Description getDescription() {
		if (description == null) {
			description = Description.createSuiteDescription(configurableEmbedder.getClass());
			description.getChildren().addAll(buildDescriptionFromStories());
		}
		return description;
	}

	@Override
	public int testCount() {
		if (testCount == 0) {
			testCount = countStories();
		}
		return testCount;
	}

	@Override
	protected Object createTest() {
		return configurableEmbedder;
	}

	private List<Description> buildDescriptionFromStories() {
		descriptionGenerator = getDescriptionGenerator();
		StoryRunner storyRunner = new StoryRunner();
		List<Description> storyDescriptions = new ArrayList<Description>();
		addSuite(storyDescriptions, "BeforeStories");
		addStories(storyDescriptions, storyRunner, descriptionGenerator);
		addSuite(storyDescriptions, "AfterStories");
		return storyDescriptions;
	}

	private void addSuite(List<Description> storyDescriptions, String name) {
		storyDescriptions.add(Description.createTestDescription(Object.class, name));
	}

	private void addStories(List<Description> storyDescriptions, StoryRunner storyRunner, JUnitDescriptionGenerator gen) {
		for (String storyPath : ((ThucydidesJUnitStories)configurableEmbedder).storyPaths()) {
			Story parseStory = storyRunner.storyOfPath(configurableEmbedder.configuration(), storyPath);
			Description descr = gen.createDescriptionFrom(parseStory);
			storyDescriptions.add(descr);
		}
	}

	private void addToStoryReporterFormats(JUnitScenarioReporter junitReporter) {
		StoryReporterBuilder storyReporterBuilder = configurableEmbedder.configuration().storyReporterBuilder();
		StoryReporterBuilder.ProvidedFormat junitReportFormat
				= new StoryReporterBuilder.ProvidedFormat(junitReporter);
		storyReporterBuilder.withFormats(junitReportFormat);
	}

	private int countStories() {
		descriptionGenerator = getDescriptionGenerator();
		return descriptionGenerator.getTestCases() + beforeAndAfterStorySteps();
	}

	private int beforeAndAfterStorySteps() {
		return 2;
	}

	private JUnitDescriptionGenerator getDescriptionGenerator() {
		if (descriptionGenerator == null) {
			descriptionGenerator = new JUnitDescriptionGenerator(configurableEmbedder.stepsFactory().createCandidateSteps(), configurableEmbedder.configuration());
		}
		return descriptionGenerator;
	}

}
