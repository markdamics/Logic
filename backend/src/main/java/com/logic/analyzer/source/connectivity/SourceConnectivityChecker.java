package com.logic.analyzer.source.connectivity;

import com.logic.analyzer.source.LogSource;
import com.logic.analyzer.source.SourceType;
import com.logic.analyzer.source.dto.ConnectionTestResult;

import java.util.Set;

public interface SourceConnectivityChecker {

    Set<SourceType> supports();

    ConnectionTestResult check(LogSource source);
}
