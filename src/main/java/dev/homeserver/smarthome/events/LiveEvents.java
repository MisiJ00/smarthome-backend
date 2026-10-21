package dev.homeserver.smarthome.events;

import java.time.Instant;

import dev.homeserver.smarthome.device.Device;
import dev.homeserver.smarthome.device.DiscoveredDevice;

/**
 * Application events the services publish when something changes. {@link EventBroadcaster} turns them into messages
 * for the connected clients after the transaction commits, filtered per client.
 */
public final class LiveEvents {

	private LiveEvents() {
	}

	/** A device reported a new state. */
	public record DeviceStateUpdated(long deviceId) {
	}

	/** A device was added ({@code before} null) or changed (name, room, locked actions). */
	public record DeviceChanged(long deviceId, Device before) {
	}

	/** A device was removed; the snapshot tells who could see it. */
	public record DeviceRemoved(Device snapshot) {
	}

	public record RoomChanged(long roomId, boolean created) {
	}

	public record RoomDeleted(long roomId) {
	}

	public record RoomsReordered() {
	}

	/** A confirmation was created, approved, rejected or expired. */
	public record ConfirmationChanged(long confirmationId) {
	}

	/** Permissions of one user, of everyone with a role, or (both null) of everyone may have changed. */
	public record PermissionsChanged(Long userId, String roleName) {
	}

	/** A user was deactivated or deleted: their connections end. */
	public record UserAccessEnded(long userId) {
	}

	/** A machine token was revoked: connections opened with it end. */
	public record TokenRevoked(long tokenId) {
	}

	/** Pairing (permit join) was started or ended in an adapter; {@code endsAt} may be unknown. */
	public record PairingChanged(String adapter, boolean active, Instant endsAt) {
	}

	/** An adapter sees a device it did not see before, i.e. one that was just paired. */
	public record DeviceDiscovered(DiscoveredDevice device) {
	}

}
