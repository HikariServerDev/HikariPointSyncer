package net.atsukigames.hikaripointsyncer.data;

import net.minecraft.network.PacketByteBuf;
import java.util.UUID;

public class SyncWaypoint {
    public UUID id;
    public String name;
    public String initial;
    public int x;
    public int y;
    public int z;
    public String dimension;
    public String author;
    public long uploadedTime;
    public boolean isDeleted;
    public long deletedTime;
    public int color; // Waypointの色コード (0-15 等)

    public SyncWaypoint() {
    }

    public SyncWaypoint(UUID id, String name, String initial, int x, int y, int z, String dimension, String author, long uploadedTime, boolean isDeleted, long deletedTime, int color) {
        this.id = id;
        this.name = name;
        this.initial = initial;
        this.x = x;
        this.y = y;
        this.z = z;
        this.dimension = dimension;
        this.author = author;
        this.uploadedTime = uploadedTime;
        this.isDeleted = isDeleted;
        this.deletedTime = deletedTime;
        this.color = color;
    }

    public void write(PacketByteBuf buf) {
        buf.writeUuid(this.id);
        buf.writeString(this.name);
        buf.writeString(this.initial);
        buf.writeInt(this.x);
        buf.writeInt(this.y);
        buf.writeInt(this.z);
        buf.writeString(this.dimension);
        buf.writeString(this.author);
        buf.writeLong(this.uploadedTime);
        buf.writeBoolean(this.isDeleted);
        buf.writeLong(this.deletedTime);
        buf.writeInt(this.color);
    }

    public static SyncWaypoint read(PacketByteBuf buf) {
        return new SyncWaypoint(
            buf.readUuid(),
            buf.readString(),
            buf.readString(),
            buf.readInt(),
            buf.readInt(),
            buf.readInt(),
            buf.readString(),
            buf.readString(),
            buf.readLong(),
            buf.readBoolean(),
            buf.readLong(),
            buf.readInt()
        );
    }
}

