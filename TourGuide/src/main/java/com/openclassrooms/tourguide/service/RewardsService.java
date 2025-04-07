package com.openclassrooms.tourguide.service;

import java.util.*;
import java.util.concurrent.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.Location;
import gpsUtil.location.VisitedLocation;
import rewardCentral.RewardCentral;
import com.openclassrooms.tourguide.user.User;
import com.openclassrooms.tourguide.user.UserReward;

@Service
public class RewardsService {
    private static final double STATUTE_MILES_PER_NAUTICAL_MILE = 1.15077945;
	private final Logger logger = LoggerFactory.getLogger(RewardsService.class);
	// proximity in miles
    private int defaultProximityBuffer = 10;
	private int proximityBuffer = defaultProximityBuffer;
	private int attractionProximityRange = 200;
	private final GpsUtil gpsUtil;
	private final RewardCentral rewardsCentral;
	private final List<Attraction> attractions;
	private final ExecutorService executor;

	public RewardsService(GpsUtil gpsUtil, RewardCentral rewardCentral) {
		this.gpsUtil = gpsUtil;
		this.rewardsCentral = rewardCentral;
		this.attractions = gpsUtil.getAttractions();
		int processors = Runtime.getRuntime().availableProcessors();
		this.executor = Executors.newFixedThreadPool(processors * 10);
	}

	public void setProximityBuffer(int proximityBuffer) {
		this.proximityBuffer = proximityBuffer;
	}

	public void setDefaultProximityBuffer() {
		proximityBuffer = defaultProximityBuffer;
	}

	/**
	 * Override calculateRewards without CountDownLatch for general use
	 * 
	 * @param user User to calculate rewards for
	 */
	public void calculateRewards(User user) {
		calculateRewards(user, null);
	}

	/**
	 * Calculates rewards for a user and signals completion via CountDownLatch
	 * Uses parallel streams for better performance
	 * 
	 * @param user User to calculate rewards for
	 * @param latch Optional CountDownLatch for test synchronization (can be null)
	 */
	public void calculateRewards(User user, CountDownLatch latch) {
		List<VisitedLocation> userLocations = user.getVisitedLocations();
		List<UserReward> userRewards = new CopyOnWriteArrayList<>(user.getUserRewards());
		Set<Attraction> attractionsToProcess = new HashSet<>();

		// Identify eligeable attractions
		attractions.parallelStream()
			.filter(attraction -> userRewards.parallelStream()
				.noneMatch(reward -> reward.attraction.attractionId.equals(attraction.attractionId)))
			.forEach(attraction -> 
				userLocations.parallelStream()
					.filter(location -> nearAttraction(location, attraction))
					.findFirst()
					.ifPresent(location -> {
						user.addUserReward(new UserReward(location, attraction)); 
						attractionsToProcess.add(attraction);
					})
			);

		// Calculate rewards
		if (!attractionsToProcess.isEmpty()) {
			calculateRewardPoints(attractionsToProcess, user, latch);
		} else if (latch != null) {
			latch.countDown();
		}
	}

	/**
	 * Process reward points calculation asynchronously
	 */
	private void calculateRewardPoints(Set<Attraction> attractions, User user, CountDownLatch latch) {
		CompletableFuture.runAsync(() -> {
			try {
				attractions.forEach(attraction -> 
					user.getUserRewards().stream()
						.filter(reward -> reward.attraction.attractionId.equals(attraction.attractionId))
						.findFirst()
						.ifPresent(reward -> {
							int points = getRewardPoints(attraction, user.getUserId());
							reward.setRewardPoints(points);
						})
				);
			} catch (Exception e) {
				logger.error("Error calculating reward points: " + e.getMessage());
			}
		}, executor).whenComplete((result, exception) -> {
			if (latch != null) {
				latch.countDown();
			}
		});
	}

	/**
	 * Gets reward points for an attraction
	 */
	public int getRewardPoints(Attraction attraction, UUID userId) {
		try {
			return rewardsCentral.getAttractionRewardPoints(attraction.attractionId, userId);
		} catch (Exception e) {
			logger.error("Error getting reward points: " + e.getMessage());
			return 0;
		}
	}

	public boolean isWithinAttractionProximity(Attraction attraction, Location location) {
		return getDistance(attraction, location) <= attractionProximityRange;
	}

	private boolean nearAttraction(VisitedLocation visitedLocation, Attraction attraction) {
		if (visitedLocation == null || visitedLocation.location == null || attraction == null) {
			return false;
		}

		if (visitedLocation.location.latitude == attraction.latitude && 
			visitedLocation.location.longitude == attraction.longitude) {
			return true;
		}
		
		return getDistance(attraction, visitedLocation.location) <= proximityBuffer;
	}

	public double getDistance(Location loc1, Location loc2) {
		double lat1 = Math.toRadians(loc1.latitude);
		double lon1 = Math.toRadians(loc1.longitude);
		double lat2 = Math.toRadians(loc2.latitude);
		double lon2 = Math.toRadians(loc2.longitude);

		double angle = Math.acos(Math.sin(lat1) * Math.sin(lat2)
				+ Math.cos(lat1) * Math.cos(lat2) * Math.cos(lon1 - lon2));

		double nauticalMiles = 60 * Math.toDegrees(angle);
		double statuteMiles = STATUTE_MILES_PER_NAUTICAL_MILE * nauticalMiles;
		return statuteMiles;
	}
	
	public double getDistance(Attraction attraction, Location location) {
		return getDistance(new Location(attraction.latitude, attraction.longitude), location);
	}

	/**
	 * Shut down the executor service properly
	 */
	public void shutdownExecutor() {
		logger.info("Shutting down RewardsService ExecutorService...");
		executor.shutdown();
		try {
			if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
				logger.warn("ExecutorService did not terminate in 10 seconds. Forcing shutdown.");
				executor.shutdownNow();
			} else {
				logger.info("ExecutorService shut down gracefully.");
			}
		} catch (InterruptedException e) {
			logger.error("Interrupted while waiting for executor shutdown", e);
			executor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}
}
