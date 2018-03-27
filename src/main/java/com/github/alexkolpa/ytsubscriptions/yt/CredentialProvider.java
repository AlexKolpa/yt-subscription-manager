package com.github.alexkolpa.ytsubscriptions.yt;

import javax.inject.Inject;
import javax.inject.Provider;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import com.google.api.client.auth.oauth2.AuthorizationCodeFlow;
import com.google.api.client.auth.oauth2.AuthorizationCodeRequestUrl;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.extensions.java6.auth.oauth2.VerificationCodeReceiver;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE, onConstructor = @__(@Inject))
public class CredentialProvider implements Provider<Credential> {

	private static final String USER_ID = "user";

	private final AtomicReference<Credential> credential = new AtomicReference<>();

	private final AuthorizationCodeFlow flow;
	private final VerificationCodeReceiver receiver;

	public void authorize(AuthorizeUrlHandler authorizeUrlHandler) throws Exception {
		try {
			Credential credential = get();
			if (credential != null) {
				this.credential.set(credential);
				return;
			}
			// open in browser
			String redirectUri = receiver.getRedirectUri();
			AuthorizationCodeRequestUrl authorizationUrl =
					flow.newAuthorizationUrl().setRedirectUri(redirectUri);
			authorizeUrlHandler.onAuthorization(authorizationUrl);
			// receive authorization code and exchange it for an access token
			String code = receiver.waitForCode();
			TokenResponse response = flow.newTokenRequest(code).setRedirectUri(redirectUri).execute();
			// store credential and return it
			credential = flow.createAndStoreCredential(response, USER_ID);
			this.credential.set(credential);
		}
		finally {
			receiver.stop();
		}
	}

	@SneakyThrows
	private synchronized Credential load() {
		Credential credential = flow.loadCredential(USER_ID);
		if (isValid(credential)) {
			return credential;
		}
		return null;
	}

	@Override
	public Credential get() {
		return credential.updateAndGet(c -> {
			if (isValid(c)) {
				return c;
			}
			else {
				return load();
			}
		});
	}

	private boolean isValid(Credential credential) {
		return credential != null && (credential.getRefreshToken() != null || credential.getExpiresInSeconds() > 60);
	}
}
