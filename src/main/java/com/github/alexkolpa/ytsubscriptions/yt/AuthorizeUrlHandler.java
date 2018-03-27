package com.github.alexkolpa.ytsubscriptions.yt;

import com.google.api.client.auth.oauth2.AuthorizationCodeRequestUrl;

public interface AuthorizeUrlHandler {
	void onAuthorization(AuthorizationCodeRequestUrl authorizationUrl);
}
