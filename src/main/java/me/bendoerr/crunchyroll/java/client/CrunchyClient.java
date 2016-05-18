package me.bendoerr.crunchyroll.java.client;

import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import me.bendoerr.crunchyroll.java.client.model.Episode;
import org.glassfish.jersey.apache.connector.ApacheConnectorProvider;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.glassfish.jersey.filter.LoggingFilter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.slf4j.MDC;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import javax.ws.rs.client.*;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static javafx.scene.input.KeyCode.M;
import static javax.ws.rs.client.Entity.entity;
import static javax.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED_TYPE;

/**
 * Blah Blah here.
 *
 * To enable to enable wire-header+entity logging set the log level for this class to TRACE. Jersey's LoggingFilter uses
 * JUL rather than SLF4J like most of us would like, so if you are using Logback or Log4j you will want to probably use
 * {@link org.slf4j.bridge.SLF4JBridgeHandler#install} or similar. Also wire logging will always log at INFO due to the
 * way Jersey's LoggingFilter is structured. Also note, that the log level will only be picked up at instantiation time.
 */
@Slf4j
public class CrunchyClient implements AutoCloseable {

    public static final String PROTO_HTTP = "http://";
    public static final String PROTO_HTTPS= "https://";
    public static final String CRUNCHY_BASE_URL = "www.crunchyroll.com";
    public static final String PATH_LOGIN = "/login";
    public static final String PATH_HISTORY = "/home/history";

    public static final String LOG_CTX_USERNAME = "CrunchyClient Username";
    public static final String LOG_CTX_BASE_URL = "CrunchyClient Base Url";
    public static final Marker LOG_MARK_LOGGING_IN_EVENT = MarkerFactory.getMarker("CrunchyClient Logging In Event");
    public static final Marker LOG_MARK_LOGGED_IN_EVENT = MarkerFactory.getMarker("CrunchClient Logged In");

    private final Set<Cookie> cookies = new HashSet<>();

    private final Client client;
    private final String crunchyBaseUrl;

    public CrunchyClient() {
        this(CRUNCHY_BASE_URL);
    }

    public CrunchyClient(
            @NonNull final String crunchyBaseUrl) {

        this.crunchyBaseUrl = crunchyBaseUrl;
        this.client = ClientBuilder.newClient(
                new ClientConfig()
                        // Use the Apache HTTP Connector to support Cookies, kind-of
                        .connectorProvider(new ApacheConnectorProvider()));

        if (log.isTraceEnabled()){
            client.register(
                    new LoggingFilter(
                            Logger.getLogger(log.getName()), true));
        }

        MDC.put(LOG_CTX_BASE_URL, this.crunchyBaseUrl);
    }

    protected <T> T get(
            @NonNull final Invocation.Builder request,
            @NonNull final Class<T> entityType) {

        cookies.forEach(request::cookie);

        final Response response = request.get();

        response.getCookies().forEach((name, newCookie) ->
                cookies.add(newCookie.toCookie()));

        return response.readEntity(entityType);
    }

    protected <T> T post(
            @NonNull final Invocation.Builder request,
            @NonNull final Entity<?> requestEntity,
            @NonNull final Class<T> responseEntityType) {

        cookies.forEach(request::cookie);

        final Response response = request.post(requestEntity);

        response.getCookies().forEach((name, newCookie) ->
                cookies.add(newCookie.toCookie()));

        return response.readEntity(responseEntityType);
    }

    public void login(
            @NonNull final String username,
            @NonNull final String password) {

        MDC.put(LOG_CTX_USERNAME, username);
        log.debug(LOG_MARK_LOGGING_IN_EVENT,
                "User {} attempting to login. Fetching CSRF token.", username);

        // We need first fire off a GET to acquire some cookies and a CSRF token
        // Cookies: c_visitor, sess_id, PHPSESSID

        final WebTarget loginTarget =
                client.target(PROTO_HTTPS  + this.crunchyBaseUrl)
                        .path(PATH_LOGIN)
                        .property(ClientProperties.FOLLOW_REDIRECTS, false);

        final InputStream loginHtml =
                get(loginTarget.request(), InputStream.class);

        final Document loginDocument;
        try {
            loginDocument = Jsoup.parse(loginHtml, null,
                    loginTarget.getUri().toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Grab the CSRF token
        final String loginToken = loginDocument
                .getElementById("login_form__token")
                .attr("value");

        if (loginToken == null || loginToken.isEmpty()) {
            log.warn(LOG_MARK_LOGGING_IN_EVENT,
                    "User {} attempting to login. Failed to fetch CSRF token.", username);
            throw new IllegalStateException("Failed to fetch CSRF token.");
        }

        log.debug(LOG_MARK_LOGGING_IN_EVENT,
                "User {} attempting to login. Received CSRF token (\"{}\").", username, loginToken);
        log.debug(LOG_MARK_LOGGING_IN_EVENT,
                "User {} attempting to login. Posting credentials.", username);

        // Now we can login.
        final Form loginForm = new Form();
        loginForm.param("login_form[name]", username);
        loginForm.param("login_form[password]", password);
        loginForm.param("login_form[redirect_url]", "/");
        loginForm.param("login_form[_token]", loginToken);

        final InputStream loggedInHtml =
                post(loginTarget.request(),
                        entity(loginForm, APPLICATION_FORM_URLENCODED_TYPE),
                        InputStream.class);

        if (!cookies.stream().anyMatch(c -> c.getName().equals("c_userid"))) {
            log.warn(LOG_MARK_LOGGING_IN_EVENT,
                    "User {} attempting to login. Failed to receive c_userid cookie.", username);
            throw new RuntimeException("Failed to login.");
        }

        log.info(LOG_MARK_LOGGED_IN_EVENT,
                "User {} logged in successfully.", username);
    }

    public List<Episode> history() {
        final WebTarget historyTarget =
                client.target(PROTO_HTTP + this.crunchyBaseUrl)
                        .path(PATH_HISTORY);

        final InputStream historyHtml =
                get(historyTarget.request(), InputStream.class);

        final Document historyDoc;
        try {
            historyDoc = Jsoup.parse(historyHtml, null, historyTarget.getUri().toString());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // Parapharsed Relevant HTML Structure
        // <div id="main_content">
        //   ...
        //   <ul>
        //     <li>
        //       ...
        //       <span itemprop="name">Show Title</span>
        //       <p class="short-desc">Episode # - Episode Title</p>
        //     </li>
        //     ...
        //   </ul>
        // </div>

        return historyDoc.getElementById("main_content")
                .getElementsByTag("ul")
                .first()
                .getElementsByTag("li")
                .stream()
                .map((historyEp) -> {
                    final String title = historyEp.getElementsByAttributeValue("itemprop", "name").text();
                    final String epDesc = historyEp.getElementsByClass("short-desc").first().text();
                    final int dashIdx = epDesc.indexOf("-");
                    return new Episode(
                            title,
                            epDesc.substring(8, dashIdx).trim(),
                            epDesc.substring(dashIdx + 1).trim());
                })
                .collect(Collectors.toList());
    }

    @Override
    public void close() throws Exception {
        client.close();
    }
}
