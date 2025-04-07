package com.openclassrooms.tourguide;

import static org.assertj.core.api.Fail.fail;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.time.StopWatch;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import gpsUtil.GpsUtil;
import gpsUtil.location.Attraction;
import gpsUtil.location.VisitedLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rewardCentral.RewardCentral;
import com.openclassrooms.tourguide.helper.InternalTestHelper;
import com.openclassrooms.tourguide.service.RewardsService;
import com.openclassrooms.tourguide.service.TourGuideService;
import com.openclassrooms.tourguide.user.User;

public class TestPerformance {

	/*
	 * A note on performance improvements:
	 * 
	 * The number of users generated for the high volume tests can be easily
	 * adjusted via this method:
	 * 
	 * InternalTestHelper.setInternalUserNumber(100000);
	 * 
	 * 
	 * These tests can be modified to suit new solutions, just as long as the
	 * performance metrics at the end of the tests remains consistent.
	 * 
	 * These are performance metrics that we are trying to hit:
	 * 
	 * highVolumeTrackLocation: 100,000 users within 15 minutes:
	 * assertTrue(TimeUnit.MINUTES.toSeconds(15) >=
	 * TimeUnit.MILLISECONDS.toSeconds(stopWatch.getTime()));
	 *
	 * highVolumeGetRewards: 100,000 users within 20 minutes:
	 * assertTrue(TimeUnit.MINUTES.toSeconds(20) >=
	 * TimeUnit.MILLISECONDS.toSeconds(stopWatch.getTime()));
	 */
	private static final Logger logger = LoggerFactory.getLogger(TestPerformance.class);
	private static GpsUtil gpsUtil;
	private static RewardCentral rewardCentral;
	private static RewardsService rewardsService;
	private static TourGuideService tourGuideService;


	@BeforeAll
	public static void setUp() {
		gpsUtil = new GpsUtil();
		rewardCentral = new RewardCentral();
		rewardsService = new RewardsService(gpsUtil, rewardCentral);
		InternalTestHelper.setInternalUserNumber(100);
		tourGuideService = new TourGuideService(gpsUtil, rewardsService);
		
		if (tourGuideService.tracker != null) {
			tourGuideService.tracker.stopTracking();
		}
	}

	@AfterAll
	public static void tearDown() {
		if (rewardsService != null) {
			rewardsService.shutdownExecutor();
		}
	}

	@Test
	public void highVolumeTrackLocation() {
		List<User> allUsers = tourGuideService.getAllUsers();

		StopWatch stopWatch = new StopWatch();
		stopWatch.start();

		CompletableFuture<Void> allTrackingFutures = tourGuideService.trackAllUserLocations(allUsers);

		try {
			allTrackingFutures.get(16, TimeUnit.MINUTES);
		} catch (Exception e) {
			fail("Error during trackAllUserLocations: " + e.getMessage());
		}

		stopWatch.stop();
		long timeInSeconds = TimeUnit.MILLISECONDS.toSeconds(stopWatch.getTime());
		logger.info("highVolumeTrackLocation: Time elapsed: {} seconds.", timeInSeconds);
		assertTrue(TimeUnit.MINUTES.toSeconds(15) >= timeInSeconds,
				"Measured time (" + timeInSeconds + "s) is more than 15 minutes.");
	}

	@Test
	public void highVolumeGetRewards() throws InterruptedException {
		Attraction attraction = gpsUtil.getAttractions().get(0);
		List<User> allUsers = tourGuideService.getAllUsers();
		int userCount = allUsers.size();

		allUsers.forEach(u -> {
			u.clearVisitedLocations();
			u.addToVisitedLocations(new VisitedLocation(u.getUserId(), attraction, new Date()));
		});
		
		StopWatch stopWatch = new StopWatch();
		stopWatch.start();

		// Use CountDownLatch for synchronisation
		CountDownLatch latch = new CountDownLatch(userCount);
		
		// Calculate rewards for each users
		allUsers.forEach(user -> rewardsService.calculateRewards(user, latch));

		// Wait for all the rewards
		boolean completed = latch.await(21, TimeUnit.MINUTES);
		
		stopWatch.stop();
		long timeInSeconds = TimeUnit.MILLISECONDS.toSeconds(stopWatch.getTime());
		logger.info("highVolumeGetRewards: Test time : {} seconds.", timeInSeconds);

		// Verify that all users have at least one reward
		for (User user : allUsers) {
			assertTrue(user.getUserRewards().size() > 0);
		}

		assertTrue(TimeUnit.MINUTES.toSeconds(20) >= timeInSeconds);

	}
}
