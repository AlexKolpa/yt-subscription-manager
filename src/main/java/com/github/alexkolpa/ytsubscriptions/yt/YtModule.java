package com.github.alexkolpa.ytsubscriptions.yt;

import javax.annotation.Nullable;
import javax.inject.Singleton;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.GeneralSecurityException;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.alexkolpa.ytsubscriptions.YtSubscriptions;
import com.google.api.client.auth.oauth2.AuthorizationCodeFlow;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.extensions.java6.auth.oauth2.VerificationCodeReceiver;
import com.google.api.client.extensions.jetty.auth.oauth2.LocalServerReceiver;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.util.store.DataStoreFactory;
import com.google.api.client.util.store.FileDataStoreFactory;
import com.google.api.services.youtube.YouTube;
import com.google.common.collect.Lists;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;

public class YtModule extends AbstractModule {

	private static final List<String> SCOPES = Lists.newArrayList("https://www.googleapis.com/auth/youtube");

	/**
	 * Application name.
	 */
	private static final String APPLICATION_NAME = "API Sample";
	/**
	 * Directory to store user credentials for this application.
	 */
	private static final File DATA_STORE_DIR = new File(YtSubscriptions.APPLICATION_CONFIG_DIR, "credentials");

	@Override
	protected void configure() {
		bind(Credential.class).toProvider(CredentialProvider.class);
	}

	@Provides
	HttpTransport httpTransport() throws GeneralSecurityException, IOException {
		return GoogleNetHttpTransport.newTrustedTransport();
	}

	@Provides
	DataStoreFactory dataStoreFactory() throws IOException {
		return new FileDataStoreFactory(DATA_STORE_DIR);
	}

	@Provides
	GoogleClientSecrets clientSecrets(JsonFactory jsonFactory) throws IOException {
		InputStream in = YtSubscriptions.class.getResourceAsStream("/client_secrets.json");
		return GoogleClientSecrets.load(jsonFactory, new InputStreamReader(in));
	}

	@Provides
	VerificationCodeReceiver receiver() {
		return new LocalServerReceiver();
	}

	@Provides
	AuthorizationCodeFlow flow(HttpTransport httpTransport, JsonFactory jsonFactory, DataStoreFactory dataStoreFactory,
			GoogleClientSecrets secrets) throws IOException {
		return new GoogleAuthorizationCodeFlow.Builder(
				httpTransport, jsonFactory, secrets, SCOPES)
				.setDataStoreFactory(dataStoreFactory)
				.setAccessType("offline")
				.build();
	}

	@Provides
	JsonFactory jsonFactory() {
		return JacksonFactory.getDefaultInstance();
	}

	@Provides
	@Nullable
	YouTube youTube(HttpTransport httpTransport, JsonFactory jsonFactory, Credential credential) {
		if (credential == null) {
			return null;
		}
		return new YouTube.Builder(httpTransport, jsonFactory, credential)
				.setApplicationName(APPLICATION_NAME)
				.build();
	}

	@Provides
	@Singleton
	ObjectMapper objectMapper() {
		return new ObjectMapper()
				.registerModule(new Jdk8Module())
				.registerModule(new JavaTimeModule());
	}
}
