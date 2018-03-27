package com.github.alexkolpa.ytsubscriptions;

import javax.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.alexkolpa.ytsubscriptions.yt.CredentialProvider;
import com.github.alexkolpa.ytsubscriptions.yt.YtModule;
import com.google.api.client.util.DateTime;
import com.google.api.client.util.Joiner;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Playlist;
import com.google.api.services.youtube.model.PlaylistItem;
import com.google.api.services.youtube.model.PlaylistItemListResponse;
import com.google.api.services.youtube.model.PlaylistItemSnippet;
import com.google.api.services.youtube.model.PlaylistListResponse;
import com.google.api.services.youtube.model.PlaylistSnippet;
import com.google.api.services.youtube.model.PlaylistStatus;
import com.google.api.services.youtube.model.ResourceId;
import com.google.api.services.youtube.model.Subscription;
import com.google.api.services.youtube.model.SubscriptionListResponse;
import com.google.api.services.youtube.model.SubscriptionSnippet;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.inject.Guice;
import com.google.inject.Injector;
import lombok.SneakyThrows;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/**
 * Flow:
 * Request or create playlist
 * Remove all items from playlist before last seen or before timestamp
 * For each subscription:
 * Add each new item after last seen or timestamp to playlist
 */
@Slf4j
public class YtSubscriptions {

	private static final Instant DEFAULT_SINCE = Instant.now().minus(7, ChronoUnit.DAYS);

	@Value
	private static class SubscriptionItem {
		private final String videoId;
		private final Instant publishedAt;
	}

	public static final File APPLICATION_CONFIG_DIR =
			new File(System.getProperty("user.home"), ".config/yt-subscriptions");
	private static final File CONFIG_FILE = new File(APPLICATION_CONFIG_DIR, "config.json");

	private static final String DEFAULT_PLAYLIST = "sub-uploads";

	public static void main(String[] args) throws Exception {
		String playlistName = args.length > 0 ? args[0] : DEFAULT_PLAYLIST;
		Instant since = null;
		if (!CONFIG_FILE.exists() && args.length <= 1) {
			log.info("First run. Checking back 1 week ago");
			since = LocalDate.now().minusWeeks(1).atStartOfDay(ZoneId.of("UTC")).toInstant();
		}
		else if (args.length > 1) {
			LocalDate date = LocalDate.parse(args[1]);
			log.info("Checking until {}", date);
			since = date.atStartOfDay(ZoneId.of("UTC")).toInstant();
		}

		Injector injector = Guice.createInjector(new YtModule());
		CredentialProvider credentialProvider = injector.getInstance(CredentialProvider.class);
		credentialProvider.authorize(authorizationUrl -> System.out.println("click link " + authorizationUrl));
		YouTube youTube = injector.getInstance(YouTube.class);

		Playlist playlist = findOwnPlaylist(youTube, playlistName)
				.orElseGet(() -> createPlaylist(youTube, playlistName));

		ObjectMapper mapper = injector.getInstance(ObjectMapper.class);
		Config config = null;
		if (CONFIG_FILE.exists()) {
			config = mapper.readValue(CONFIG_FILE, Config.class);
		}

		clearOutdated(youTube, playlist.getId());

		List<Subscription> subscriptions = listSubscriptions(youTube);
		Map<String, String> names = subscriptions.stream()
				.collect(Collectors.toMap(Subscription::getId, x -> x.getSnippet().getTitle()));
		Map<String, String> uploads = getUploads(youTube, subscriptions);
		log.info("Found {} subscriptions", subscriptions.size());
		Map<String, Instant> lastChecks = Maps.newHashMap();

		if (config != null) {
			lastChecks.putAll(config.getLastCheckTime());
		}

		List<SubscriptionItem> items = Lists.newArrayList();

		for (Map.Entry<String, String> entry : uploads.entrySet()) {
			String subscriptionId = entry.getKey();
			String uploadsId = entry.getValue();
			String channelTitle = names.get(subscriptionId);
			log.info("Checking {}", channelTitle);

			Instant uploadsSince = Optional.ofNullable(since)
					.orElse(lastChecks.getOrDefault(subscriptionId, DEFAULT_SINCE));
			List<PlaylistItem> newItems = listItems(youTube, uploadsId, uploadsSince);

			if (!newItems.isEmpty()) {
				log.info("Found {} new items for {}", newItems.size(), channelTitle);
				items.addAll(newItems.stream()
						.map(PlaylistItem::getSnippet)
						.map(snippet -> {
							Instant publishedAt = Instant.ofEpochMilli(snippet.getPublishedAt().getValue());
							String videoId = snippet.getResourceId().getVideoId();
							return new SubscriptionItem(videoId, publishedAt);
						})
						.collect(Collectors.toList()));

				Instant lastPublished = toInstant(newItems.get(0).getSnippet().getPublishedAt());
				lastChecks.put(subscriptionId, lastPublished);
			}
		}

		List<String> videoIds = items.stream()
				.sorted(Comparator.comparing(SubscriptionItem::getPublishedAt).reversed())
				.map(SubscriptionItem::getVideoId)
				.collect(Collectors.toList());

		addToPlaylist(youTube, playlist, videoIds);

		mapper.writeValue(CONFIG_FILE, new Config(lastChecks));
	}

	@SneakyThrows
	private static Playlist createPlaylist(YouTube youTube, String playlistName) {
		Playlist playlist = new Playlist();
		PlaylistSnippet snippet = new PlaylistSnippet();
		snippet.setTitle(playlistName);
		playlist.setSnippet(snippet);
		PlaylistStatus status = new PlaylistStatus();
		status.setPrivacyStatus("private");
		playlist.setStatus(status);
		return youTube.playlists()
				.insert("snippet,status", playlist)
				.execute();
	}

	private static void clearOutdated(YouTube youTube, String playlistId) throws IOException {
		List<PlaylistItem> items = listItems(youTube, playlistId, null);

		log.info("Clearing {} entries", items.size());
		for (PlaylistItem remove : items) {
			youTube.playlistItems()
					.delete(remove.getId())
					.execute();
		}
	}

	private static void addToPlaylist(YouTube youTube, Playlist playlist, List<String> newActivity)
			throws IOException {
		for (String videoId : newActivity) {
			PlaylistItem playlistItem = new PlaylistItem();
			PlaylistItemSnippet snippet = new PlaylistItemSnippet();
			snippet.setPlaylistId(playlist.getId());
			ResourceId resourceId = new ResourceId();
			resourceId.set("kind", "youtube#video");
			resourceId.set("videoId", videoId);
			snippet.setResourceId(resourceId);
			playlistItem.setSnippet(snippet);
			youTube.playlistItems()
					.insert("snippet", playlistItem)
					.execute();
		}
	}

	private static List<Subscription> listSubscriptions(YouTube youTube) throws IOException {
		List<Subscription> subscriptions = Lists.newArrayList();
		String pageToken = null;
		do {
			SubscriptionListResponse response = youTube.subscriptions()
					.list("snippet")
					.setPageToken(pageToken)
					.setMine(true)
					.execute();

			subscriptions.addAll(response.getItems());
			pageToken = response.getNextPageToken();
		}
		while (pageToken != null);
		return subscriptions;
	}

	private static List<PlaylistItem> listItems(YouTube youTube, String playlistId,
			@Nullable Instant since) throws IOException {
		List<PlaylistItem> items = Lists.newArrayList();
		String pageToken = null;
		do {
			PlaylistItemListResponse response = youTube.playlistItems()
					.list("snippet")
					.setPageToken(pageToken)
					.setPlaylistId(playlistId)
					.execute();

			boolean shouldStop = false;
			for (PlaylistItem item : response.getItems()) {
				Instant publishedAt = toInstant(item.getSnippet().getPublishedAt());
				if (since != null && publishedAt.isBefore(since)) {
					shouldStop = true;
					break;
				}

				items.add(item);
			}

			if (shouldStop) {
				break;
			}

			pageToken = response.getNextPageToken();
		}
		while (pageToken != null);

		return items;
	}

	private static Optional<Playlist> findOwnPlaylist(YouTube youTube, String name) throws IOException {
		String pageToken = null;
		do {
			PlaylistListResponse response = youTube.playlists()
					.list("snippet")
					.setPageToken(pageToken)
					.setMaxResults(50L)
					.setMine(true)
					.execute();

			Playlist result = response.getItems()
					.stream()
					.filter(playlist -> playlist.getSnippet().getTitle().equals(name))
					.findAny()
					.orElse(null);
			if (result != null) {
				return Optional.of(result);
			}

			pageToken = response.getNextPageToken();
		}
		while (pageToken != null);
		return Optional.empty();
	}

	private static Map<String, String> getUploads(YouTube youTube,
			List<Subscription> subscriptionList) throws IOException {
		Map<String, String> uploadIds = Maps.newHashMap();
		for (List<Subscription> subscriptions : Lists.partition(subscriptionList, 50)) {
			Map<String, String> channelSubscriptions = subscriptions.stream()
					.collect(Collectors.toMap(x -> x.getSnippet().getResourceId().getChannelId(),
							Subscription::getId));
			String channelIds = Joiner.on(',').join(channelSubscriptions.keySet());

			uploadIds.putAll(youTube.channels()
					.list("contentDetails")
					.setId(channelIds)
					.setMaxResults(50L)
					.execute()
					.getItems()
					.stream()
					.collect(Collectors.toMap(x -> channelSubscriptions.get(x.getId()),
							x -> x.getContentDetails().getRelatedPlaylists().getUploads())));
		}

		return uploadIds;
	}

	private static Instant toInstant(DateTime time) {
		return Instant.ofEpochMilli(time.getValue());
	}
}
