package me.wiefferink.errorsink;

import io.sentry.Sentry;
import io.sentry.SentryClient;
import io.sentry.dsn.InvalidDsnException;
import me.wiefferink.errorsink.editors.Breadcrumbs;
import me.wiefferink.errorsink.editors.PluginInformation;
import me.wiefferink.errorsink.editors.RuleData;
import me.wiefferink.errorsink.editors.ServerInformation;
import me.wiefferink.errorsink.editors.StackInformation;
import me.wiefferink.errorsink.filters.ErrorSinkFilter;
import me.wiefferink.errorsink.filters.RuleFilter;
import me.wiefferink.errorsink.tools.Analytics;
import me.wiefferink.errorsink.tools.Log;
import me.wiefferink.errorsink.tools.Utils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// TODO try changing System.err logging to level error instead of warn?
// TODO command to reload config?
public class ErrorSink extends JavaPlugin {

	private static ErrorSink instance;
	private SentryClient sentryClient;
	private BukkitSentryAppender appender;
	private Map<String, EventRuleMatcher> matcherMap;
	private int messagesSent = 0;
	private BukkitSentryClientFactory bukkitSentryClientFactory;
	public static boolean hasOldLog4j2;

	/**
	 * Constructor
	 * Collection is already enabled here instead of onEnable to also capture
	 * loading bugs in other plugins (like missing dependencies)
	 */
	public ErrorSink() {
		instance = this;
		Log.setLogger(getLogger());
		Log.setDebug(getConfig().getBoolean("debug"));
		saveDefaultConfig();

		List<String> oldLogVersions = Arrays.asList("1.7", "1.8", "1.9", "1.10", "1.11");
		for(String oldLogVersion : oldLogVersions) {
			String version = Bukkit.getBukkitVersion();
			// Detects '1.8', '1.8.3', '1.8-pre1' style versions
			if(version.equals(oldLogVersion)
					|| version.startsWith(oldLogVersion + ".")
					|| version.startsWith(oldLogVersion + "-")) {
				hasOldLog4j2 = true;
				break;
			}
		}
		Log.debug("Has old Log4j2:", hasOldLog4j2);

		bukkitSentryClientFactory = new BukkitSentryClientFactory();

		String dsn = getConfig().getString("dsn");
		if(dsn == null || dsn.isEmpty()) {
			Log.error("Provide a DSN from Sentry.io in the config to get started!");
			return;
		}

		List<String> matchSectionNames = Arrays.asList("events.filters", "events.rules", "breadcrumbs.filters", "breadcrumbs.rules");
		matcherMap = new HashMap<>();
		for(String matchSectionName : matchSectionNames) {
			ConfigurationSection matchSection = getConfig().getConfigurationSection(matchSectionName);
			if(matchSection != null) {
				for(String eventRuleKey : matchSection.getKeys(false)) {
					matcherMap.put(matchSection.getCurrentPath() + "." + eventRuleKey, new EventRuleMatcher(matchSection.getConfigurationSection(eventRuleKey), getConfig().getConfigurationSection("parts")));
				}
			}
		}

		startCollecting(dsn);
	}

	@Override
	public void onEnable() {
		ErrorSink.getInstance().getServer().getPluginManager().registerEvents(bukkitSentryClientFactory, ErrorSink.getInstance());
		// All plugins loaded now, update packages
		bukkitSentryClientFactory.updateInAppFrames();
		// Services should now be registered
		Utils.run(bukkitSentryClientFactory::updateInAppFrames);
		// Just to be sure
		Utils.run(20L, bukkitSentryClientFactory::updateInAppFrames);

		if(getConfig().getBoolean("sendStats")) {
			Analytics.start();
		}
	}

	@Override
	public void onDisable() {
		// Remove and shutdown appender to prevent double appenders because of disable/enable
		Logger logger = (Logger)LogManager.getRootLogger();
		if(appender != null) {
			logger.removeAppender(appender);
			appender.stop();
		}

		HandlerList.unregisterAll(this);
	}

	/**
	 * Get the currenlty active instance of ErrorSink
	 * @return ErrorSink instance
	 */
	public static ErrorSink getInstance() {
		return instance;
	}

	/**
	 * Add a message sent
	 */
	public void increaseMessageSent() {
		messagesSent++;
	}

	/**
	 * Get the message count sent since last reset
	 *
	 * @return Messages sent since last reset
	 */
	public int getAndResetMessageSent() {
		int result = messagesSent;
		messagesSent = 0;
		return result;
	}

	/**
	 * Start collecting events
	 * @param dsn The Sentry DSN to use to send the events
	 */
	public void startCollecting(String dsn) {
		// Setup connection to Sentry.io
		try {
			sentryClient = Sentry.init(dsn, new BukkitSentryClientFactory());
		} catch(InvalidDsnException | IllegalArgumentException e) {
			Log.error("Provided Sentry DSN is invalid:", ExceptionUtils.getStackTrace(e));
			return;
		}
		Logger logger = (Logger)LogManager.getRootLogger();

		// Start collecting errors from the Logger
		appender = new BukkitSentryAppender();

		// Filters
		appender.addFilter(new ErrorSinkFilter());
		appender.addFilter(new RuleFilter());


		// Editors
		appender.addEventEditor(new RuleData());
		appender.addEventEditor(new StackInformation());
		appender.addEventEditor(new ServerInformation());
		appender.addEventEditor(new PluginInformation());

		// Default data
		sentryClient.setServerName(getServerName());
		sentryClient.setRelease(getRelease());

		// Start the collector
		appender.start();
		logger.addAppender(appender);

		// Add later to prevent reported messages also appearing in the breadcrumbs
		appender.addEventEditor(new Breadcrumbs(logger));
	}

	/**
	 * Get the server name that should be used
	 * @return The server name
	 */
	public String getServerName() {
		String serverName = getConfig().getString("serverName");
		if(serverName == null || serverName.isEmpty()) {
			serverName = Bukkit.getServerName();
		}

		// Server name can never be null/empty, this will cause Raven to lookup the hostname and kills the server somehow
		if (serverName == null || serverName.isEmpty()) {
			serverName = "ServerName";
		}
		return serverName;
	}

	/**
	 * Get the release to use
	 * @return The release (version of the ErrorSink plugin)
	 */
	public String getRelease() {
		return getDescription().getVersion();
	}

	/**
	 * Get the Raven instance, for example for adding extra BuilderHelpers
	 * @return The used Raven instance
	 */
	public SentryClient getSentryClient() {
		return sentryClient;
	}

	/**
	 * Match a rule to an event
	 *
	 * @param matcherPath The path of the matcher rules to use
	 * @param message     The message to match
	 * @param level       The level to match
	 * @param throwable   The exception to match
	 * @param threadName  The thread name to match
	 * @param loggerName  The logger name to match
	 * @return A map with the captured groups if a match is found, otherwise null
	 */
	public Map<String, String> match(String matcherPath, String message, Level level, Throwable throwable, String threadName, String loggerName) {
		EventRuleMatcher matcher = matcherMap.get(matcherPath);
		if(matcher == null) {
			Log.error("Trying to match path", matcherPath, "but there is no EventRuleMatcher!");
			return null;
		}

		return matcher.matches(message, level, throwable, threadName, loggerName);
	}

	/**
	 * Get the timestamp from a LogEvent
	 * @param event LogEvent to get the timestamp from
	 * @return Timestamp of the LogEvent
	 */
	public long getTimeStamp(LogEvent event) {
		// Changed event.getTimeMillis() to event.getMillis(), Minecraft 1.11 and lower uses log4j 2.0-beta9, Sentry builds with 2.5
		if(hasOldLog4j2) {
			try {
				Method method = event.getClass().getMethod("getMillis");
				return (long) method.invoke(event);
			} catch(NoSuchMethodException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
				Log.debug("Failed to use getMillis on LogEvent:", ExceptionUtils.getStackTrace(e));
				// Return something that is kind of close
				return Calendar.getInstance().getTimeInMillis();
			}
		}

		// Normal new log4j
		return event.getTimeMillis();
	}

}
