package pt.ipsantarem.esgts.covid19tracker.server.scraping;

import org.jsoup.nodes.Document;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pt.ipsantarem.esgts.covid19tracker.server.callbacks.UpdateAvailableListener;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.LocalDate;
import java.util.Date;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.Callable;

import static pt.ipsantarem.esgts.covid19tracker.server.utils.DateUtils.*;

/**
 * Handles the update of the World in Data document (in short, when a new batch of COVID-19 related data is inserted). This
 * class can be called by a ExecutorService (which submits a series of tasks handled by one or more threads), since it
 * implements the {@link Callable} interface.
 *
 * @author José Simões
 */
public class WorldInDataDocumentUpdateHandler implements Callable<String> {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldInDataDocumentUpdateHandler.class);

    private Document worldInDataDocument;
    private UpdateAvailableListener updateAvailableListener;

    public WorldInDataDocumentUpdateHandler() {
        worldInDataDocument = WorldInDataDocument.getInstance();
    }

    public WorldInDataDocumentUpdateHandler(UpdateAvailableListener updateAvailableListener) {
        worldInDataDocument = WorldInDataDocument.getInstance();
        this.updateAvailableListener = updateAvailableListener;
    }

    /**
     * Check for changes on the World in Data page and compare the date of those changes to the last time where we checked
     * for changes locally.
     *
     * @return A empty CSV of virus stat records if the World in Data document returned null of if there are no updates
     * to the page since we last checked, or a CSV with the new records if the page was changed since we last checked it.
     */
    @Override
    public String call() {
        // if the document is null, just return a empty csv and try again later. it means that no connection could be
        // established.
        if (worldInDataDocument == null) {
            LOGGER.warn("World in Data document is null, skipping the change check!");
            return "";
        }

        // if there is a connection, we open the locally stored database file that contains the last time we downloaded
        // the updates on the world in data page and compare it to the update time in the said page.
        try (DB db = DBMaker.fileDB("lasttime.db").fileMmapEnable().make()) {
            Map<String, Date> lastCheckedUpdate = db
                    .hashMap("lastTimeDownloadedUpdates", Serializer.STRING, Serializer.DATE)
                    .createOrOpen();
            Date lastTimeDownloaded = lastCheckedUpdate.get("lastTimeDownloaded");

            // if the last time we downloaded the updates is null, that means we never downloaded them in the first place.
            // add the current date as the time we checked for updates and proceed to download the csv record.
            if (lastTimeDownloaded == null) {
                LOGGER.info("First run of the server, downloading the CSV!");
                lastCheckedUpdate.put("lastTimeDownloaded", localDateToDate(LocalDate.now()));
                return downloadRecords();
            }

            // get the element containing the date of the document update
            String pageDate = worldInDataDocument.getElementsByClass("last-updated")
                    .get(0)
                    .child(0)
                    .child(0)
                    .text();

            LocalDate lastTimeUpdatedInPage;

            try {
                // parse and convert the string date to a LocalDate
                lastTimeUpdatedInPage = parseStringToLocalDate(pageDate);
            } catch (ParseException pe) {
                throw new RuntimeException(pe);
            }

            // parse the java.util.Date db record and convert it to a LocalDate
            LocalDate lastTimeDownloadedUpdates = dateToLocalDate(lastTimeDownloaded);

            // if the time where the records were updated on the page is bigger than the time where we last downloaded
            // the updates, it means there are new records. update the last time we downloaded the csv and then download
            // it.
            if (lastTimeUpdatedInPage.compareTo(lastTimeDownloadedUpdates) > 0) {
                LOGGER.info("New updates are available, downloading them!");
                lastCheckedUpdate.put("lastTimeDownloaded", localDateToDate(LocalDate.now()));

                String csv = downloadRecords();

                if (updateAvailableListener != null) {
                    updateAvailableListener.onUpdateAvailable(csv);
                }

                return csv;
            }

            LOGGER.info("No updates found!");

            // no updates, return empty csv
            return "";
        }
    }

    /**
     * Actually download the records
     *
     * @return A CSV with a list of the virus stats
     */
    private String downloadRecords() {
        URL urlToDownload;

        try {
            urlToDownload = new URL("https://covid.ourworldindata.org/data/owid-covid-data.csv");
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }

        String csv;

        try {
            // open a url connection to the csv file
            HttpURLConnection conn = (HttpURLConnection) urlToDownload.openConnection();

            // get the input stream of the url connection
            try (InputStream data = new BufferedInputStream(conn.getInputStream())) {

                // convert it to a String type using the java.util.Scanner class
                try (Scanner scanner = new Scanner(data, StandardCharsets.UTF_8.name())) {
                    csv = scanner.useDelimiter("\\A").next();
                }
            }
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }

        // finally, return CSV
        return csv;
    }
}
