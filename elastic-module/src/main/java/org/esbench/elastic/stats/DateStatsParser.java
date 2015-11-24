package org.esbench.elastic.stats;

import static org.esbench.elastic.stats.DateStatsParserConstants.DATE_SEPARATOR;
import static org.esbench.elastic.stats.DateStatsParserConstants.EPOCH_MILLIS;
import static org.esbench.elastic.stats.DateStatsParserConstants.EPOCH_SECOND;
import static org.esbench.elastic.stats.DateStatsParserConstants.ES_FORMATS;
import static org.esbench.elastic.stats.DateStatsParserConstants.FORMAT_PROP;
import static org.esbench.elastic.stats.DateStatsParserConstants.JODA_FORMAT_TO_JDK;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.elasticsearch.search.aggregations.metrics.stats.extended.ExtendedStats;
import org.esbench.generator.field.meta.DateFieldMetadata;
import org.esbench.generator.field.meta.FieldMetadata;
import org.esbench.generator.field.meta.MetaType;
import org.esbench.generator.field.meta.MetadataConstants;
import org.esbench.generator.field.meta.NumericFieldMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Based on defined mapping
 */
public class DateStatsParser implements ExtendedStatsParser<FieldMetadata> {

	private static final Logger LOGGER = LoggerFactory.getLogger(DateStatsParser.class);

	@Override
	public FieldMetadata parse(FieldInfo info, ExtendedStats stats, int valuesPerDocument) {
		long from = Math.round(stats.getMin());
		long to = Math.round(stats.getMax());

		String[] esPatterns = info.getJson().path(FORMAT_PROP).asText(MetadataConstants.DEFAULT_DATE_PATTERN).split(DATE_SEPARATOR);
		String pattern = jodaToJDK(info, esPatterns);
		Instant fromInstant = Instant.ofEpochMilli(from);
		Instant toInstant = Instant.ofEpochMilli(to);
		ChronoUnit unit = calculateTimeStep(fromInstant, toInstant, stats.getCount());

		if(pattern != null) {
			long increment = unit.between(fromInstant, toInstant) / stats.getCount();
			return new DateFieldMetadata(info.getFullPath(), valuesPerDocument, fromInstant, toInstant, increment, unit, pattern);
		} else {
			return jodaToNumericUnit(info.getFullPath(), valuesPerDocument, fromInstant, toInstant, unit, esPatterns);
		}
	}

	private NumericFieldMetadata jodaToNumericUnit(String name, int valPerDoc, Instant from, Instant to, ChronoUnit unit, String... esPatterns) {
		if(ArrayUtils.contains(esPatterns, EPOCH_MILLIS)) {
			long incrementInMilis = Math.max(Duration.of(1, unit).toMillis(), 1L);
			return new NumericFieldMetadata(name, valPerDoc, MetaType.LONG, from.toEpochMilli(), to.toEpochMilli(), incrementInMilis);
		}
		if(ArrayUtils.contains(esPatterns, EPOCH_SECOND)) {
			long incrementInSeconds = Math.max(Duration.of(1, unit).toMinutes() * 60, 1L);
			return new NumericFieldMetadata(name, valPerDoc, MetaType.INTEGER, from.getEpochSecond(), to.getEpochSecond(), incrementInSeconds);
		}
		throw new IllegalArgumentException("Unsupported formats: " + ArrayUtils.toString(esPatterns));
	}

	private String jodaToJDK(FieldInfo info, String[] esPatterns) {
		for(String pattern : esPatterns) {
			if(ES_FORMATS.contains(pattern)) {
				continue;
			}
			try {
				DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
				LOGGER.debug("Choose {} date format for field {}", formatter.toString(), info.getFullPath());
				return pattern;
			} catch (IllegalArgumentException ex) {
				LOGGER.trace("Failed to parse {} date format for field {}: {}", pattern, info.getFullPath(), ex.getMessage());
			}
		}
		for(String pattern : esPatterns) {
			String key = pattern.replaceFirst("strict", StringUtils.EMPTY).replace("_", StringUtils.EMPTY).toLowerCase();
			String format = JODA_FORMAT_TO_JDK.get(key);
			if(format != null) {
				LOGGER.debug("Choose {} date format for field {}", format, info.getFullPath());
				return format;
			}
		}
		return null;
	}

	/**
	 * Calculate for given instants appropriate ChronoUnit which satisfy this criteria (from + unit * numOfDocs) < to.
	 * @param from instant defining lowest value in date range
	 * @param to instant defining highest value in date range
	 * @param numOfDocs which affects how big unit is  
	 * @return ChronoUnit never null
	 */
	ChronoUnit calculateTimeStep(Instant from, Instant to, long numOfDocs) {
		for(int i = ChronoUnit.DAYS.ordinal(); i >= 0; i--) {
			ChronoUnit unit = ChronoUnit.values()[i];
			long duration = unit.between(from, to);
			if(duration > numOfDocs) {
				return unit;
			}
		}
		return ChronoUnit.HOURS;
	}
}
