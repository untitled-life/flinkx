package com.dtstack.flinkx.carbondata.writer.dict;

import org.apache.flink.api.java.tuple.Tuple2;
import java.sql.Date;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * Helper functions for converting between internal and external date and time representations.
 * Dates are exposed externally as java.sql.Date and are represented internally as the number of
 * dates since the Unix epoch (1970-01-01). Timestamps are exposed externally as java.sql.Timestamp
 * and are stored internally as longs, which are capable of storing timestamps with 100 nanosecond
 * precision.
 */
public class DateTimeUtils {

    /** see http://stackoverflow.com/questions/466321/convert-unix-timestamp-to-julian
     *it's 2440587.5, rounding up to compatible with Hive
     */
    public static final int JULIAN_DAY_OF_EPOCH = 2440588;

    public static final long SECONDS_PER_DAY = 60 * 60 * 24L;

    public static final long MICROS_PER_SECOND = 1000L * 1000L;

    public static final long NANOS_PER_SECOND = MICROS_PER_SECOND * 1000L;

    public static final long MICROS_PER_DAY = MICROS_PER_SECOND * SECONDS_PER_DAY;

    public static final long MILLIS_PER_DAY = SECONDS_PER_DAY * 1000L;

    /** number of days in 400 years */
    public static final int daysIn400Years = 146097;

    /** number of days between 1.1.1970 and 1.1.2001 */
    public static final int to2001 = -11323;

    /** this is year -17999, calculation: 50 * daysIn400Year */
    public static final int YearZero = -17999;

    public static final int toYearZero = to2001 + 7304850;

    public static final TimeZone TimeZoneGMT = TimeZone.getTimeZone("GMT");

    public static final Set MonthOf31Days = new HashSet();

    public static final TimeZone defaultTimeZone = TimeZone.getDefault();

    public static final ThreadLocal<TimeZone> threadLocalLocalTimeZone = new ThreadLocal<TimeZone>() {
        @Override
        public TimeZone initialValue() {
            return Calendar.getInstance().getTimeZone();
        }
    };

    public static final ThreadLocal<DateFormat> threadLocalTimestampFormat = new ThreadLocal<DateFormat>() {
        @Override
        public SimpleDateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        }
    };

    public static final ThreadLocal<DateFormat> threadLocalDateFormat = new ThreadLocal<DateFormat>() {
        @Override
        public SimpleDateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        }
    };

    static {
        MonthOf31Days.addAll(Arrays.asList(1, 3, 5, 7, 8, 10, 12));
    }

    private DateTimeUtils() {
        // hehe
    }

    /**
     * Converts Timestamp to string according to Hive TimestampWritable convention.
     */
    public static String timestampToString(long us) {
        Timestamp ts = toJavaTimeStamp(us);
        String timestampString = ts.toString();
        String formatted = threadLocalTimestampFormat.get().format(ts);

        if(timestampString.length() > 19 && !timestampString.substring(19).equals(".0")) {
            formatted += timestampString.substring(19);
        }
        return formatted;
    }

    /**
     * Returns a java.sql.Timestamp from number of micros since epoch.
     */
    public static Timestamp toJavaTimeStamp(long us) {
        // setNanos() will overwrite the millisecond part, so the milliseconds should be
        // cut off at seconds
        long seconds = us / MICROS_PER_SECOND;
        long micros = us % MICROS_PER_SECOND;
        // setNanos() can not accept negative value
        if (micros < 0) {
            micros += MICROS_PER_SECOND;
            seconds -= 1;
        }
        Timestamp t = new Timestamp(seconds * 1000);
        t.setNanos((int) micros * 1000);
        return t;
    }


    public static String dateToString(int days) {
        return threadLocalDateFormat.get().format(toJavaDate(days));
    }


    public static Date toJavaDate(int daysSinceEpoch) {
        return new Date(daysToMillis(daysSinceEpoch));
    }


    /**
     * reverse of millisToDays
     * @param days
     * @return
     */
    public static long daysToMillis(int days) {
        long millisLocal = (long)days * MILLIS_PER_DAY;
        return millisLocal - getOffsetFromLocalMillis(millisLocal, threadLocalLocalTimeZone.get());
    }

    /**
     * Lookup the offset for given millis seconds since 1970-01-01 00:00:00 in given timezone.
     * TODO: Improve handling of normalization differences.
     * TODO: Replace with JSR-310 or similar system - see SPARK-16788
     */
    public static long getOffsetFromLocalMillis(long millisLocal, TimeZone tz) {
        int guess = tz.getRawOffset();
        // the actual offset should be calculated based on milliseconds in UTC
        int offset = tz.getOffset(millisLocal - guess);
        if (offset != guess) {
            // fallback to do the reverse lookup using java.sql.Timestamp
            // this should only happen near the start or end of DST
            int days = (int) Math.floor((double)millisLocal / MILLIS_PER_DAY);
            int year = getYear(days);
            int month = getMonth(days);
            int day = getDayOfMonth(days);

            int millisOfDay = (int) (millisLocal % MILLIS_PER_DAY);
            if (millisOfDay < 0) {
                millisOfDay += MILLIS_PER_DAY;
            }
            int seconds = (int) (millisOfDay / 1000L);
            int hh = seconds / 3600;
            int mm = seconds / 60 % 60;
            int ss = seconds % 60;
            int ms = millisOfDay % 1000;
            Calendar calendar = Calendar.getInstance(tz);
            calendar.set(year, month - 1, day, hh, mm, ss);
            calendar.set(Calendar.MILLISECOND, ms);
            guess = (int) (millisLocal - calendar.getTimeInMillis());
        }
        return guess;
    }


    /**
     * Returns the year value for the given date. The date is expressed in days
     * since 1.1.1970.
     */
    public static int getYear(int date) {
        return getYearAndDayInYear(date).getField(0);
    }


    /**
     * Returns the 'day in year' value for the given date. The date is expressed in days
     * since 1.1.1970.
     */
    public static int getDayInYear(int date) {
        return getYearAndDayInYear(date).getField(1);
    }


    /**
     * Calculates the year and and the number of the day in the year for the given
     * number of days. The given days is the number of days since 1.1.1970.
     *
     * The calculation uses the fact that the period 1.1.2001 until 31.12.2400 is
     * equals to the period 1.1.1601 until 31.12.2000.
     */
    public static Tuple2<Integer,Integer> getYearAndDayInYear(int daysSince1970) {
        int daysNormalized = daysSince1970 + toYearZero;
        int numOfQuarterCenturies = daysNormalized / daysIn400Years;
        int daysInThis400 = daysNormalized % daysIn400Years + 1;
        Tuple2<Integer,Integer> tuple2 = numYears(daysInThis400);
        int years = tuple2.getField(0);
        int dayInYear = tuple2.getField(1);
        int year = (2001 - 20000) + 400 * numOfQuarterCenturies + years;
        return new Tuple2<>(year, dayInYear);
    }


    /**
     * Calculates the number of years for the given number of days. This depends
     * on a 400 year period.
     * @param days days since the beginning of the 400 year period
     * @return (number of year, days in year)
     */
    public static Tuple2<Integer,Integer> numYears(int days) {
        int year = days / 365;
        int boundary = yearBoundary(year);
        if(days > boundary) {
            return new Tuple2<>(year, days - boundary);
        } else {
            return new Tuple2<>(year - 1, days - yearBoundary(year - 1));
        }
    }

    public static int yearBoundary(int year) {
        return year * 365 + ((year / 4 ) - (year / 100) + (year / 400));
    }

    public static boolean isLeapYear(int year) {
        return (year % 4) == 0 && ((year % 100) != 0 || (year % 400) == 0);
    }


    /**
     * Returns the month value for the given date. The date is expressed in days
     * since 1.1.1970. January is month 1.
     */
    public static int getMonth(int date) {
        Tuple2<Integer,Integer> tuple2 = getYearAndDayInYear(date);
        int year = tuple2.getField(0);
        int dayInYear = tuple2.getField(1);
        if (isLeapYear(year)) {
            if (dayInYear == 60) {
                return 2;
            } else if (dayInYear > 60) {
                dayInYear = dayInYear - 1;
            }
        }
        if (dayInYear <= 31) {
            return 1;
        } else if (dayInYear <= 59) {
            return 2;
        } else if (dayInYear <= 90) {
            return 3;
        } else if (dayInYear <= 120) {
            return 4;
        } else if (dayInYear <= 151) {
            return 5;
        } else if (dayInYear <= 181) {
            return 6;
        } else if (dayInYear <= 212) {
            return 7;
        } else if (dayInYear <= 243) {
            return 8;
        } else if (dayInYear <= 273) {
            return 9;
        } else if (dayInYear <= 304) {
            return 10;
        } else if (dayInYear <= 334) {
            return 11;
        } else {
            return 12;
        }
    }


    /**
     * Returns the 'day of month' value for the given date. The date is expressed in days
     * since 1.1.1970.
     */
    public static int getDayOfMonth(int date) {
        Tuple2<Integer,Integer> tuple2 = getYearAndDayInYear(date);
        int year = tuple2.getField(0);
        int dayInYear = tuple2.getField(1);
        if (isLeapYear(year)) {
            if (dayInYear == 60) {
                return 29;
            } else if (dayInYear > 60) {
                dayInYear = dayInYear - 1;
            }
        }

        if (dayInYear <= 31) {
            return dayInYear;
        } else if (dayInYear <= 59) {
            return dayInYear - 31;
        } else if (dayInYear <= 90) {
            return dayInYear - 59;
        } else if (dayInYear <= 120) {
            return dayInYear - 90;
        } else if (dayInYear <= 151) {
            return dayInYear - 120;
        } else if (dayInYear <= 181) {
            return dayInYear - 151;
        } else if (dayInYear <= 212) {
            return dayInYear - 181;
        } else if (dayInYear <= 243) {
            return dayInYear - 212;
        } else if (dayInYear <= 273) {
            return dayInYear - 243;
        } else if (dayInYear <= 304) {
            return dayInYear - 273;
        } else if (dayInYear <= 334) {
            return dayInYear - 304;
        } else {
            return dayInYear - 334;
        }

    }


    /**
     * we should use the exact day as Int, for example, (year, month, day) -> day
     * @param millisUtc
     * @return
     */
    public static int millisToDays(long millisUtc) {
        // SPARK-6785: use Math.floor so negative number of days (dates before 1970)
        // will correctly work as input for function toJavaDate(Int)
        long millisLocal = millisUtc + threadLocalLocalTimeZone.get().getOffset(millisUtc);
        return (int) Math.floor((double)millisLocal / MILLIS_PER_DAY);
    }

}
