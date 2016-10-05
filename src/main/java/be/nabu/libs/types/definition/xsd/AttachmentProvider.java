package be.nabu.libs.types.definition.xsd;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;

public interface AttachmentProvider {
	public OutputStream getOutput(String namespace) throws IOException;
	public URI getURI(String namespace);
}
