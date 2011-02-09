/*
Because it is hard to force one thread to do something before another in testing, I
simply used example simulations that show that certain situations that should [not]
happen actually [don't] happen. Simulations include 2 children, (1 adults, 2 children),
and (5 adults, 3 children). Most testing was done by choosing random seeds and checking
if it passes.

"nachos" = ../bin/nachos

Scenario 1
An adult cannot get on boat if first child has not gone on boat (even if only 1 child appeared)
nachos =>
	Adult (#7) appeared on Oahu
	Adult (#7) failed to get on boat
	Child (#9) appeared on Oahu
	Child (#9) got on boat (rower)
	...

Scenario 2
If two children are on the boat, another child cannot get on
nachos =>
	...
	Child (#15) got on boat (rower)
	Child (#16) appeared on Oahu
	Child (#16) got on boat (rider)
	Child (#17) appeared on Oahu
	Child (#17) failed to get on boat
	...

Scenario 3
An adult needs to get on boat if only one child on Oahu (unless very first child)
nachos -s 1 =>
	...
	Child (#14) got off boat, state => [A, C]  Oahu:[5,1] Molokai:[0,2]
	Child (#13) failed to get on boat
	Child (#12) failed to get on boat
	Adult (#7) got on boat
	**Adult rowing to Molokai.
	...

Scenario 4
An adult should not get on the boat unless only one child on Oahu
nachos -s 325 =>
	...
	Child (#13) appeared on Oahu
	Child (#14) appeared on Oahu
	Adult (#11) failed to get on boat
	...

Scenario 5
A child cannot get on the boat if the boat is on the other island
nachos =>
	...
	**Child arrived on Molokai as a passenger.
	Child (#16) got off boat, state => [A, C]  Oahu:[5,1] Molokai:[0,2]
	Child (#15) got on boat
	**Child rowing to Oahu.
	Child (#15) got off boat, state => [A, C]  Oahu:[5,2] Molokai:[0,1]
	Child (#16) failed to get on boat
	...
*/

package nachos.threads;

import nachos.machine.Lib;
import nachos.ag.BoatGrader;
import java.util.ArrayList;

public class Boat {

	public static void selfTest() {
		//Check top of page for examples of more testing.
		BoatGrader b = new BoatGrader();
		
		System.out.println("\n ******************Testing Boats with only 2 children******************");
		begin(0, 2, b);

		System.out.println("\n ******************Testing Boats with 2 children, 1 adult******************");
		begin(1, 2, b);

		System.out.println("\n ******************Testing Boats with 3 children, 5 adults******************");
		begin(5, 3, b);
	}

	public static void begin(int adults, int children, BoatGrader b) {
		Lib.assertTrue(adults >= 0 && children >= 2);
		
		// Store the externally generated autograder in a class
		// variable to be accessible by children.
		bg = b;
		
		// Instantiate global variables here
		adultsOnOahu = childrenOnOahu = adultsOnMolokai = childrenOnMolokai = 0;
		firstChildAppearedOnOahu = gameOver = false;
		boatLocation = Oahu;
		boatLock = new Lock();
		notOneChildAndEmptyBoatOnOahu = new Condition(boatLock);
		oneChildAndEmptyBoatOnOahu = new Condition(boatLock);
		emptyBoatOnMolokai = new Condition(boatLock);
		riderInBoat = new Condition(boatLock);
		doneRowing = new Condition(boatLock);
		
		dummyLock = new Lock();
		gamePossiblyOver = new Condition(dummyLock);
		dummyLock.acquire();
		
		// Create threads here. See section 3.4 of the Nachos for Java
		// Walkthrough linked from the projects page.
		
		for (int i = 0; i < adults; i++) {
			KThread adultThread = new KThread(new Person(Adult));
			adultThread.setName("Adult");
			adultThread.fork();
		}
		for (int i = 0; i < children; i++) {
			KThread childThread = new KThread(new Person(Child));
			childThread.setName("Child");
			childThread.fork();
		}
		//what if dummyLock.acquire() was here? then some child might already think game is over so switch sleep with if...else
		while (!gameOver) {
			gamePossiblyOver.sleep();
			if (childrenOnMolokai + adultsOnMolokai == adults + children)
				gameOver = true;
			else {
				System.out.println("Game is not over yet.");
				emptyBoatOnMolokai.wake();	
			}
		}
		dummyLock.release();
		System.out.println("Game over!");
	}
	
	static void printState() {
		System.out.println("[A, C]  Oahu:[" + adultsOnOahu + "," + childrenOnOahu + "]" + " Molokai:[" + adultsOnMolokai + "," + childrenOnMolokai + "]");
	}
	
	static void AdultItinerary(Person adult) {
		/*
		 * This is where you should put your solutions. Make calls to the
		 * BoatGrader to show that it is synchronized. For example:
		 * bg.AdultRowToMolokai(); indicates that an adult has rowed the boat
		 * across to Molokai
		 */
		Lib.assertTrue(adult.location == Oahu);	// adult here should always be on Oahu
		
		adultsOnOahu++;		// could just do this in Person constructor but maybe cheating?
		System.out.print(KThread.currentThread() + " appeared on Oahu, state => ");
		printState();
		addAdultToBoat(adult);
		bg.AdultRowToMolokai();
		adultRowToMolokai(adult);
	}
	
	static void ChildItinerary(Person child) {
		childrenOnOahu++;	// could just do this in Person constructor but maybe cheating?
		System.out.print(KThread.currentThread() + " appeared on Oahu, state => ");
		printState();
		while (!gameOver) {
			if (child.location == Oahu) {
				if (addPairPartToBoat(child)) {
					bg.ChildRowToMolokai();
					childRowToMolokai(child);
				}
				else {
					bg.ChildRideToMolokai();
					childRideToMolokai(child);
				}
			}
			else if (child.peopleSeenOnPreviousIsland != 0) {	// go back to Oahu
				addChildToBoat(child);
				bg.ChildRowToOahu();
				childRowToOahu(child);
			}
			KThread.yield();
		}
	}

	static void addAdultToBoat(Person adult) {
		boatLock.acquire();
		while (childrenOnOahu != 1 || boatLocation != Oahu || !boat.isEmpty() || !firstChildAppearedOnOahu) {
			System.out.println(KThread.currentThread() + " failed to get on boat");
			oneChildAndEmptyBoatOnOahu.sleep();
		}
		System.out.println(KThread.currentThread() + " got on boat");
		boat.add(adult);
		boatLock.release();
	}
	static void addChildToBoat(Person child) {
		boatLock.acquire();
		while (boatLocation != Molokai || !boat.isEmpty() || gameOver) {
			System.out.println(KThread.currentThread() + " failed to get on boat");
			emptyBoatOnMolokai.sleep();
		}
		System.out.println(KThread.currentThread() + " got on boat");
		boat.add(child);
		boatLock.release();
	}
	static boolean addPairPartToBoat(Person child) {	// returns true if child is rower
		boatLock.acquire();
		if (!firstChildAppearedOnOahu)
			firstChildAppearedOnOahu = true;
		else
			while (childrenOnOahu == 1 || boatLocation != Oahu || boat.size() >= 2) {
				System.out.println(KThread.currentThread() + " failed to get on boat");
				notOneChildAndEmptyBoatOnOahu.sleep();
			}
		boat.add(child);
		if (boat.size() == 2) { //child is rider
			System.out.println(KThread.currentThread() + " got on boat (rider)");
			riderInBoat.wake();
			while (boat.size() == 2)
				doneRowing.sleep();
			boatLock.release();
			return false;
		}
		else { //child is rower
			System.out.println(KThread.currentThread() + " got on boat (rower)");
			while (boat.size() != 2)
				riderInBoat.sleep();
			boatLock.release();
			return true;
		}
	}
	static void removeFromBoat(Person person) {
		boatLock.acquire();
		System.out.print(KThread.currentThread() + " got off boat, state => ");
		printState();
		boat.remove(person);
		if (boat.isEmpty()) {
			if (boatLocation == Molokai) {
				if (person.peopleSeenOnPreviousIsland == 0) {
					dummyLock.acquire();
					gamePossiblyOver.wake();
					dummyLock.release();
				}
				else
					emptyBoatOnMolokai.wake();
			}
			else if (childrenOnOahu == 1)
				oneChildAndEmptyBoatOnOahu.wake();
			else
				notOneChildAndEmptyBoatOnOahu.wake();
		}
		boatLock.release();
	}
	static void adultRowToMolokai(Person adult) {
		adult.peopleSeenOnPreviousIsland = childrenOnOahu + adultsOnOahu - 1;
		adultsOnOahu--;
		adultsOnMolokai++;
		boatLocation = Molokai;
		removeFromBoat(adult);
		adult.location = Molokai;
	}
	static void childRowToMolokai(Person child) {
		child.peopleSeenOnPreviousIsland = childrenOnOahu + adultsOnOahu - 2;
		childrenOnOahu--;
		childrenOnMolokai++;
		boatLocation = Molokai;
		removeFromBoat(child);
		child.location = Molokai;
		
		boatLock.acquire();
		doneRowing.wake();
		boatLock.release();
	}
	static void childRideToMolokai(Person child) {
		child.peopleSeenOnPreviousIsland = childrenOnOahu + adultsOnOahu - 1;
		childrenOnOahu--;
		childrenOnMolokai++;
		boatLocation = Molokai;
		removeFromBoat(child);
		child.location = Molokai;
	}
	static void childRowToOahu(Person child) {
		child.peopleSeenOnPreviousIsland = childrenOnMolokai + adultsOnMolokai - 1;
		childrenOnMolokai--;
		childrenOnOahu++;
		boatLocation = Oahu;
		removeFromBoat(child);
		child.location = Oahu;
	}
	
	enum PersonType { Adult, Child }
	enum Location { Oahu, Molokai }
	static BoatGrader bg;
	static PersonType Adult = PersonType.Adult,		//simply for convenience
					  Child = PersonType.Child;
	static Location Oahu = Location.Oahu,
					Molokai = Location.Molokai;
	static Location boatLocation;
	static ArrayList<Person> boat = new ArrayList<Person>();
	static int adultsOnOahu,
			   childrenOnOahu,
			   adultsOnMolokai,
			   childrenOnMolokai;
	static Lock boatLock, dummyLock;
	static Condition notOneChildAndEmptyBoatOnOahu,
					 oneChildAndEmptyBoatOnOahu,
					 riderInBoat, doneRowing,	// for children in pairs
					 emptyBoatOnMolokai,
					 gamePossiblyOver;			// communicate with begin()
	static boolean firstChildAppearedOnOahu,	// has the first child gone on the boat?
				   gameOver;
	static class Person implements Runnable {
		PersonType type;
		Location location;
		int peopleSeenOnPreviousIsland;
		Person(PersonType t) {
			location = Oahu;
			type = t;
		}
		public void run() {
			if (type == Adult)
				AdultItinerary(this);
			else
				ChildItinerary(this);
		}
	}
}