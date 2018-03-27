package com.github.alexkolpa.ytsubscriptions;

import java.time.Instant;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Config {
	private Map<String, Instant> lastCheckTime;
}
