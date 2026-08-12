package dev.chronit.via;

import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import net.raphimc.vialoader.netty.VLPipeline;
import org.geysermc.mcprotocollib.network.NetworkConstants;

/**
 * Places the translation codec correctly within MCProtocolLib's channel pipeline.
 *
 * <p>ViaLoader positions its codec relative to the host's own handlers, so it needs their names.
 * These come from {@link NetworkConstants} rather than being hard-coded strings, so a rename in the
 * protocol library becomes a compile error rather than a connection that silently fails to
 * translate.
 */
final class ChronitVLPipeline extends VLPipeline {

    ChronitVLPipeline(UserConnection user, ProtocolVersion version) {
        super(user, version);
    }

    @Override
    protected String packetCodecName() {
        return NetworkConstants.CODEC_NAME;
    }

    @Override
    protected String lengthCodecName() {
        return NetworkConstants.SIZER_NAME;
    }

    @Override
    protected String compressionCodecName() {
        return NetworkConstants.COMPRESSION_NAME;
    }
}
