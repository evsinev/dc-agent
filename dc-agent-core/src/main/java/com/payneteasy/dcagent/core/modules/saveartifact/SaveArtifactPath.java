package com.payneteasy.dcagent.core.modules.saveartifact;

import com.payneteasy.dcagent.core.config.model.TSaveArtifactConfig;
import com.payneteasy.dcagent.core.util.SafeFiles;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.util.regex.Pattern;

import static com.payneteasy.dcagent.core.util.Strings.hasText;

/**
 * Where an uploaded artifact is stored: {@code <dir>/<version>.<extension>}. {@code version} and
 * the {@code x-dc-agent-file-extension} header come from the request, so the result must stay
 * inside {@code dir} — also through links inside it. The canonical check cannot see a dangling
 * link as the last component, so {@link #write} opens the file without following links.
 */
public final class SaveArtifactPath {

    /** A file extension from the request: a name part, never a path. */
    private static final Pattern EXTENSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private SaveArtifactPath() {
    }

    /**
     * @param aVersion         the {@code {version}} segment of the URL
     * @param aHeaderExtension the {@code x-dc-agent-file-extension} header, may be null
     */
    public static File resolve(TSaveArtifactConfig aConfig, String aVersion, String aHeaderExtension) {
        if (aVersion.contains("..")) {
            throw new IllegalArgumentException("Name contains '..' - " + aVersion);
        }
        String filename = hasText(aConfig.getReplaceDirChars())
                ? aVersion.replace(aConfig.getReplaceDirChars(), "/")
                : aVersion;

        String extension;
        if (hasText(aHeaderExtension)) {
            if (!EXTENSION.matcher(aHeaderExtension).matches() || aHeaderExtension.contains("..")) {
                throw new IllegalArgumentException("Header x-dc-agent-file-extension must be a plain extension like 'tar.gz', got '" + aHeaderExtension + "'");
            }
            extension = aHeaderExtension;
        } else {
            extension = aConfig.getExtension();
        }

        return SafeFiles.createFileGuarded(new File(aConfig.getDir()), filename + "." + extension);
    }

    /** Writes the artifact; a symbolic link at the file's place (dangling or not) fails the write. */
    public static void write(File aFile, InputStream aIn) throws IOException {
        try (OutputStream out = Files.newOutputStream(aFile.toPath()
                , StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE
                , LinkOption.NOFOLLOW_LINKS)) {
            aIn.transferTo(out);
        }
    }
}
