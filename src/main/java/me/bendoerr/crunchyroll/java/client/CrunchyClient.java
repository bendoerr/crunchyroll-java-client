package me.bendoerr.crunchyroll.java.client;

import lombok.NonNull;
import me.bendoerr.crunchyroll.java.client.model.Episode;
import org.glassfish.jersey.apache.connector.ApacheConnectorProvider;
import org.glassfish.jersey.client.ClientConfig;
import org.glassfish.jersey.client.ClientProperties;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Invocation;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Cookie;
import javax.ws.rs.core.Form;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static javax.ws.rs.client.Entity.entity;
import static javax.ws.rs.core.MediaType.APPLICATION_FORM_URLENCODED_TYPE;


public class CrunchyClient implements AutoCloseable {

    public static final String PROTO_HTTP = "http://";
    public static final String PROTO_HTTPS= "https://";
    public static final String CRUNCHY_BASE_URL = "www.crunchyroll.com";
    public static final String PATH_LOGIN = "/login";
    public static final String PATH_HISTORY = "/home/history";

    private final Set<Cookie> cookies = new HashSet<Cookie>();

    private final Client client;
    private final String crunchyBaseUrl;

    public CrunchyClient(
            @NonNull final String username,
            @NonNull final String password) {

        this(username, password, CRUNCHY_BASE_URL);
    }

    public CrunchyClient(
            @NonNull final String username,
            @NonNull final String password,
            @NonNull final String crunchyBaseUrl) {

        client = ClientBuilder.newClient(
                new ClientConfig()
                        // Use the Apache HTTP Connector to support Cookies
                        .connectorProvider(new ApacheConnectorProvider()));

        this.crunchyBaseUrl = crunchyBaseUrl;

        login(username, password);
    }

    protected void login(
            @NonNull final String username,
            @NonNull final String password) {

        // We need first fire off a GET to acquire some cookies and a CSRF token
        // Cookies: c_visitor, sess_id, PHPSESSID

        final WebTarget loginTarget =
                client.target(PROTO_HTTPS  + this.crunchyBaseUrl)
                        .path(PATH_LOGIN)
                        .property(ClientProperties.FOLLOW_REDIRECTS, true);

        final InputStream loginHtml =
                loginTarget.request()
                        .get(InputStream.class);

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

        // Now we can login.
        final Form loginForm = new Form();
        loginForm.param("login_form[name]", username);
        loginForm.param("login_form[password]", password);
        loginForm.param("login_form[redirect_url]", "/");
        loginForm.param("login_form[_token]", loginToken);

        final Response loggedInResponse = loginTarget
                .request()
                .post(entity(loginForm, APPLICATION_FORM_URLENCODED_TYPE));

        // Stash the cookies away.
        loggedInResponse.getCookies().forEach((name, cookie) ->
                cookies.add(cookie));

        if (!loggedInResponse.getCookies().containsKey("c_userid"))
            throw new RuntimeException("Failed to login.");
    }


    public List<Episode> history() {
        final WebTarget historyTarget =
                client.target(PROTO_HTTP + this.crunchyBaseUrl)
                        .path(PATH_HISTORY);

        final Invocation.Builder request = historyTarget.request();
        cookies.forEach(request::cookie);

        final InputStream historyHtml =
                request.get(InputStream.class);

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
                            epDesc.substring(0, dashIdx).trim(),
                            epDesc.substring(dashIdx + 1).trim());
                })
                .collect(Collectors.toList());
    }

    @Override
    public void close() throws Exception {
        client.close();
    }
}
