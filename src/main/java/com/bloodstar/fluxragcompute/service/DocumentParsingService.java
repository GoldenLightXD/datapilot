package com.bloodstar.fluxragcompute.service;

import com.bloodstar.fluxragcompute.dto.ParsedDocument;
import java.io.InputStream;

public interface DocumentParsingService {

    ParsedDocument parse(InputStream inputStream, String filename, String contentType);
}
