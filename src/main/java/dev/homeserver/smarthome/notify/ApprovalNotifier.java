package dev.homeserver.smarthome.notify;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

import dev.homeserver.smarthome.audit.Via;
import dev.homeserver.smarthome.confirmation.Confirmation;
import dev.homeserver.smarthome.confirmation.ConfirmationRepository;
import dev.homeserver.smarthome.confirmation.ConfirmationService;
import dev.homeserver.smarthome.confirmation.ConfirmationStatus;
import dev.homeserver.smarthome.device.Device;
import dev.homeserver.smarthome.device.DeviceRepository;
import dev.homeserver.smarthome.events.LiveEvents.ConfirmationChanged;
import dev.homeserver.smarthome.grant.Action;
import dev.homeserver.smarthome.trusteddevice.TrustedDeviceService;
import dev.homeserver.smarthome.user.CurrentUser;
import dev.homeserver.smarthome.user.User;
import dev.homeserver.smarthome.user.UserRepository;

/**
 * Sends a push message over ntfy when a confirmation is created: to everyone who may decide it, and to the requester
 * when they asked from somewhere other than the app and can approve with their phone (e.g. from a widget or the web).
 * Sending happens after the commit, in the background; a failing ntfy only loses the push, never the request.
 */
@Component
public class ApprovalNotifier {

	private static final Logger log = LoggerFactory.getLogger(ApprovalNotifier.class);

	/** Opens the app (its URL scheme) when the notification is tapped. */
	static final String CLICK_URL = "smarthome://";

	private final NtfyProperties properties;
	private final NtfyTopics topics;
	private final ConfirmationRepository confirmations;
	private final ConfirmationService confirmationService;
	private final TrustedDeviceService trustedDevices;
	private final UserRepository users;
	private final DeviceRepository devices;
	private final TransactionTemplate transactions;
	private final RestClient http;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	public ApprovalNotifier(NtfyProperties properties, NtfyTopics topics, ConfirmationRepository confirmations,
			ConfirmationService confirmationService, TrustedDeviceService trustedDevices, UserRepository users,
			DeviceRepository devices, TransactionTemplate transactions) {
		this.properties = properties;
		this.topics = topics;
		this.confirmations = confirmations;
		this.confirmationService = confirmationService;
		this.trustedDevices = trustedDevices;
		this.users = users;
		this.devices = devices;
		this.transactions = transactions;
		var requests = new SimpleClientHttpRequestFactory();
		requests.setConnectTimeout(Duration.ofSeconds(5));
		requests.setReadTimeout(Duration.ofSeconds(10));
		this.http = RestClient.builder().requestFactory(requests).build();
	}

	/** One message to one user's topic, in ntfy's JSON publish format. */
	public record Push(String topic, String title, String message, List<String> tags, int priority, String click) {
	}

	@TransactionalEventListener(fallbackExecution = true)
	public void on(ConfirmationChanged event) {
		if (!properties.enabled()) {
			return;
		}
		executor.execute(() -> {
			try {
				for (Push push : pushesFor(event.confirmationId())) {
					send(push);
				}
			}
			catch (RuntimeException e) {
				log.warn("Push for confirmation {} failed: {}", event.confirmationId(), e.toString());
			}
		});
	}

	/** Whom to tell about a pending confirmation, and what. Empty once it is decided. */
	List<Push> pushesFor(long confirmationId) {
		return transactions.execute(status -> {
			var confirmation = confirmations.findById(confirmationId).orElse(null);
			if (confirmation == null || confirmation.getStatus() != ConfirmationStatus.PENDING) {
				return List.<Push>of();
			}
			String requester = users.findById(confirmation.getUserId()).map(User::getName).orElse("Jemand");
			String device = devices.findById(confirmation.getDeviceId()).map(Device::getName).orElse("ein Gerät");
			String what = device + " " + verb(confirmation);
			List<String> tags = List.of(confirmation.getAction() == Action.UNLOCK ? "door" : "bell");
			var pushes = new ArrayList<Push>();
			for (User user : users.findAllByOrderByNameAscIdAsc()) {
				if (!user.isActive() || user.getIdentityId() == null) {
					continue;
				}
				if (user.getId() == confirmation.getUserId()) {
					if (confirmation.getVia() != Via.APP && trustedDevices.hasActive(user.getId())) {
						pushes.add(new Push(topics.of(user.getId()), "Deine Anfrage freigeben",
								what + ": mit dem Fingerabdruck freigeben", tags, 4, CLICK_URL));
					}
				}
				else if (confirmationService.canDecide(CurrentUser.of(user, null), confirmation)) {
					pushes.add(new Push(topics.of(user.getId()), "Freigabe angefragt", requester + " möchte " + what,
							tags, 4, CLICK_URL));
				}
			}
			return pushes;
		});
	}

	private void send(Push push) {
		var request = http.post().uri(properties.url()).contentType(MediaType.APPLICATION_JSON).body(push);
		if (properties.token() != null && !properties.token().isBlank()) {
			request = request.header("Authorization", "Bearer " + properties.token());
		}
		request.retrieve().toBodilessEntity();
	}

	private static String verb(Confirmation confirmation) {
		return confirmation.getAction() == Action.UNLOCK ? "öffnen" : "schalten";
	}

}
