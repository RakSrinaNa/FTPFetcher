package fr.raksrinana.ftpfetcher.cli;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.ext.NioPathDeserializer;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jetbrains.annotations.NotNull;
import java.nio.file.Path;

@SuppressWarnings("FieldMayBeFinal")
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@NoArgsConstructor
public class FolderSettings{
	@JsonProperty("recursive")
	private boolean recursive = false;
	@JsonProperty("fileFilter")
	@NotNull
	private String fileFilter = ".*";
	@JsonProperty("localFolder")
	@JsonDeserialize(using = NioPathDeserializer.class)
	@NotNull
	private Path localFolder;
	@JsonProperty("ftpFolder")
	@NotNull
	private String ftpFolder;
	@JsonProperty("deleteOnSuccess")
	private boolean deleteOnSuccess = false;
}
