package flightapp;

import java.io.IOException;
import java.nio.channels.AlreadyConnectedException;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import static flightapp.PasswordUtils.plaintextMatchesHash;

/**
 * Runs queries against a back-end database
 */
public class Query extends QueryAbstract {
  //
  // Canned queries
  //
  private boolean loggedIn;
  private boolean canBook = false;
  List<Itinerary> itineraries = new ArrayList<>();
  private String username;

  //  private static final String FLIGHT_CAPACITY_SQL = "SELECT capacity FROM FLIGHTS_vprak WHERE fid = ?";
  private static final String FLIGHT_CAPACITY_SQL = "SELECT capacity FROM FLIGHTS WHERE fid = ?";

  private PreparedStatement flightCapacityStmt;

//  private static final String CLEAR_FROM_USERS_SQL = "DELETE * FROM USERS_vprak";
//  private PreparedStatement clearFromUsersStmt;
//
//  private static final String CLEAR_FROM_RESERVATIONS_SQL = "DELETE * FROM RESERVATIONS_vprak";
//  private PreparedStatement clearFromReservationsStmt;

  //  private static final String CREATE_CUSTOMER_SQL = "INSERT INTO USERS_vprak (username, password, balance) VALUES (?, ?, ?)";
  private static final String CREATE_CUSTOMER_SQL = "INSERT INTO USERS_vprak VALUES (?, ?, ?)";
  private PreparedStatement createCustomerStmt;

  private static final String CHECK_CUSTOMER_ALREADYIN_SQL = "SELECT COUNT(*) as count FROM USERS_vprak U WHERE UPPER(U.username) = UPPER(?)";
  private PreparedStatement checkCustomerStmt;

  private static final String CHECK_PASSWORDS_EQUAL_SQL = "SELECT U.password as password FROM USERS_vprak U WHERE UPPER(U.username) = UPPER(?)";
  private PreparedStatement checkPasswordsEqualStmt;

  private static final String DIRECT_FLIGHTS_SQL = "SELECT TOP (?) * FROM FLIGHTS WHERE origin_city = ? AND dest_city = ? AND day_of_month = ? AND canceled != 1 ORDER BY actual_time ASC";
  private PreparedStatement directFlightsStmt;

  private static final String INDIRECT_FLIGHTS_SQL = "SELECT TOP (?) * FROM FLIGHTS as f1, FLIGHTS as f2 WHERE f1.origin_city = ? AND f2.dest_city = ? AND f1.dest_city = f2.origin_city AND f1.day_of_month = ? AND f2.day_of_month = ? AND f1.canceled != 1 AND f2.canceled != 1 ORDER BY f1.actual_time + f2.actual_time ASC";
  private PreparedStatement indirectFlightsStmt;

  private static final String ALREADY_HAS_ITINERARY_SQL = "SELECT COUNT(*) as count FROM RESERVATIONS_vprak as r, Flights as f WHERE r.username = ? AND r.fid1 = f.fid AND f.day_of_month = ?";
  private PreparedStatement alreadyHasItineraryStmt;

  //  private static final String UPDATE_RESERVATIONS_SQL = "INSERT INTO RESERVATIONS_vprak (rid, paid, fid1, fid2, username) VALUES (?, ?, ?, ?, ?)";
  private static final String UPDATE_RESERVATIONS_SQL = "INSERT INTO RESERVATIONS_vprak VALUES (?, ?, ?, ?, ?, ?)";

  private PreparedStatement updateReservationsStmt;

  private static final String NUM_RESERVATIONS_SQL = "SELECT COUNT(*) FROM RESERVATIONS_vprak";
  private PreparedStatement numReservationsStmt;

  private static final String FIND_RESERVATION_SQL = "SELECT * FROM RESERVATIONS_vprak WHERE rid = ? AND paid = 0";
  private PreparedStatement findReservationStmt;

  private static final String FIND_USER_SQL = "SELECT * FROM USERS_vprak WHERE UPPER(username) = UPPER(?)";
  private PreparedStatement findUserStmt;

  private static final String UPDATE_USER_BALANCE_SQL = "UPDATE USERS_vprak SET balance = ? WHERE UPPER(username) = UPPER(?)";
  private PreparedStatement updateUserBalanceStmt;

  private static final String UPDATE_RESERVATIONS_PAID_SQL = "UPDATE RESERVATIONS_vprak SET paid = 1 WHERE rid = ?";
  private PreparedStatement updateReservationsPaidStmt;

  private static final String USER_RESERVATIONS_SQL = "SELECT * FROM RESERVATIONS_vprak WHERE UPPER(username) = ?";
  private PreparedStatement userReservationsStmt;

  private static final String GET_FLIGHT_SQL = "SELECT * FROM FLIGHTS WHERE fid = ?";
  private PreparedStatement getFlightStmt;

  private static final String RESERVATION_CAPACITY_DIRECT_SQL = "SELECT COUNT(*) FROM RESERVATIONS_vprak WHERE fid1 = ? AND fid2 IS NULL";
  private PreparedStatement reservationCapacityDirectStmt;

  private static final String RESERVATION_CAPACITY_INDIRECT_SQL = "SELECT COUNT(*) FROM RESERVATIONS_vprak WHERE fid1 = ? AND fid2 = ?";
  private PreparedStatement reservationCapacityIndirectStmt;

  //
  // Instance variables
  //


  protected Query() throws SQLException, IOException {
    loggedIn = false;
    prepareStatements();
  }

  /**
   * Clear the data in any custom tables created.
   *
   * WARNING! Do not drop any tables and do not clear the flights table.
   */
  public void clearTables() {
    try {
      PreparedStatement clearFromUsersStmt = conn.prepareStatement("DELETE FROM USERS_vprak");
      PreparedStatement clearFromReservationsStmt = conn.prepareStatement("DELETE FROM RESERVATIONS_vprak");
      clearFromReservationsStmt.executeUpdate();
      clearFromUsersStmt.executeUpdate();
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  /*
   * prepare all the SQL statements in this method.
   */
  private void prepareStatements() throws SQLException {
    flightCapacityStmt = conn.prepareStatement(FLIGHT_CAPACITY_SQL);
//    clearFromUsersStmt = conn.prepareStatement(CLEAR_FROM_USERS_SQL);
//    clearFromReservationsStmt = conn.prepareStatement(CLEAR_FROM_RESERVATIONS_SQL);

    createCustomerStmt = conn.prepareStatement(CREATE_CUSTOMER_SQL);
    checkCustomerStmt = conn.prepareStatement(CHECK_CUSTOMER_ALREADYIN_SQL);

    checkPasswordsEqualStmt = conn.prepareStatement(CHECK_PASSWORDS_EQUAL_SQL);

    directFlightsStmt = conn.prepareStatement(DIRECT_FLIGHTS_SQL);
    indirectFlightsStmt = conn.prepareStatement(INDIRECT_FLIGHTS_SQL);

    alreadyHasItineraryStmt = conn.prepareStatement(ALREADY_HAS_ITINERARY_SQL);
    updateReservationsStmt = conn.prepareStatement(UPDATE_RESERVATIONS_SQL);
    numReservationsStmt = conn.prepareStatement(NUM_RESERVATIONS_SQL);
    reservationCapacityDirectStmt = conn.prepareStatement(RESERVATION_CAPACITY_DIRECT_SQL);
    reservationCapacityIndirectStmt = conn.prepareStatement(RESERVATION_CAPACITY_INDIRECT_SQL);

    findReservationStmt = conn.prepareStatement(FIND_RESERVATION_SQL);
    findUserStmt = conn.prepareStatement(FIND_USER_SQL);
    updateUserBalanceStmt = conn.prepareStatement(UPDATE_USER_BALANCE_SQL);
    updateReservationsPaidStmt = conn.prepareStatement(UPDATE_RESERVATIONS_PAID_SQL);

    userReservationsStmt = conn.prepareStatement(USER_RESERVATIONS_SQL);
    getFlightStmt = conn.prepareStatement(GET_FLIGHT_SQL);
  }

  /**
   * Takes a user's username and password and attempts to log the user in.
   *
   * @param username user's username
   * @param password user's password
   *
   * @return If someone has already logged in, then return "User already logged in\n".  For all
   *         other errors, return "Login failed\n". Otherwise, return "Logged in as [username]\n".
   */
  public String transaction_login(String username, String password) {
    // TODO: YOUR CODE HERE
    if (loggedIn) {return "User already logged in\n";}

    try {
      checkCustomerStmt.clearParameters();
      checkCustomerStmt.setString(1, username.toUpperCase());
      ResultSet alreadyIn = checkCustomerStmt.executeQuery();
      alreadyIn.next();
      if (alreadyIn.getInt("count") == 0) {return "Login failed\n";}
      checkPasswordsEqualStmt.setString(1, username);
      ResultSet passwordsEqual = checkPasswordsEqualStmt.executeQuery();
      passwordsEqual.next();
      byte[] passwordHashed = passwordsEqual.getBytes("password");
      if (plaintextMatchesHash(password, passwordHashed)) {
        loggedIn = true;
        this.username = username;
        canBook = false;
        return "Logged in as " + username + "\n";
      }
    } catch (SQLException e) {
      e.printStackTrace();
    }

    return "Login failed\n";
  }

  /**
   * Implement the create user function.
   *
   * @param username   new user's username. User names are unique the system.
   * @param password   new user's password.
   * @param initAmount initial amount to deposit into the user's account, should be >= 0 (failure
   *                   otherwise).
   *
   * @return either "Created user {@code username}\n" or "Failed to create user\n" if failed.
   */
  public String transaction_createCustomer(String username, String password, int initAmount) {
    // TODO: YOUR CODE HERE
    try {
      if (initAmount < 0) {return "Failed to create user\n";}
      checkCustomerStmt.clearParameters();
      checkCustomerStmt.setString(1, username);
      ResultSet alreadyIn = checkCustomerStmt.executeQuery();
      alreadyIn.next();
      int count = alreadyIn.getInt("count");
      if (count != 0) {return "Failed to create user\n";}

      createCustomerStmt.clearParameters();
      createCustomerStmt.setString(1, username.toUpperCase());
      createCustomerStmt.setBytes(2, PasswordUtils.hashPassword(password));
      createCustomerStmt.setInt(3, initAmount);
      createCustomerStmt.executeUpdate();
      return "Created user " + username + "\n";
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "Failed to create user\n";
  }

  /**
   * Implement the search function.
   *
   * Searches for flights from the given origin city to the given destination city, on the given
   * day of the month. If {@code directFlight} is true, it only searches for direct flights,
   * otherwise is searches for direct flights and flights with two "hops." Only searches for up
   * to the number of itineraries given by {@code numberOfItineraries}.
   *
   * The results are sorted based on total flight time.
   *
   * @param originCity
   * @param destinationCity
   * @param directFlight        if true, then only search for direct flights, otherwise include
   *                            indirect flights as well
   * @param dayOfMonth
   * @param numberOfItineraries number of itineraries to return, must be positive
   *
   * @return If no itineraries were found, return "No flights match your selection\n". If an error
   *         occurs, then return "Failed to search\n".
   *
   *         Otherwise, the sorted itineraries printed in the following format:
   *
   *         Itinerary [itinerary number]: [number of flights] flight(s), [total flight time]
   *         minutes\n [first flight in itinerary]\n ... [last flight in itinerary]\n
   *
   *         Each flight should be printed using the same format as in the {@code Flight} class.
   *         Itinerary numbers in each search should always start from 0 and increase by 1.
   *
   * @see Flight#toString()
   */
  public String transaction_search(String originCity, String destinationCity,
                                   boolean directFlight, int dayOfMonth,
                                   int numberOfItineraries) {
    // WARNING: the below code is insecure (it's susceptible to SQL injection attacks) AND only
    // handles searches for direct flights.  We are providing it *only* as an example of how
    // to use JDBC; you are required to replace it with your own secure implementation.
    //
    // TODO: YOUR CODE HERE

    StringBuffer sb = new StringBuffer();
    try {
      itineraries.clear();
      if (numberOfItineraries <= 0) {throw new IllegalArgumentException();}
      List<Itinerary> allFlights = new ArrayList<>();

      // one hop itineraries
//      String unsafeSearchSQL = "SELECT TOP (" + numberOfItineraries
//        + ") day_of_month,carrier_id,flight_num,origin_city,dest_city,actual_time,capacity,price "
//        + "FROM Flights " + "WHERE origin_city = \'" + originCity + "\' AND dest_city = \'"
//        + destinationCity + "\' AND day_of_month =  " + dayOfMonth + " " + "AND canceled != 1"
//        + "ORDER BY actual_time ASC";

//      String unsafeSearchSQL = "SELECT TOP (" + numberOfItineraries + ") *"
//              + "FROM Flights " + "WHERE origin_city = \'" + originCity + "\' AND dest_city = \'"
//              + destinationCity + "\' AND day_of_month =  " + dayOfMonth + " " + "AND canceled != 1"
//              + "ORDER BY actual_time ASC";



//      Statement searchStatement = conn.createStatement();

      directFlightsStmt.clearParameters();
      directFlightsStmt.setInt(1, numberOfItineraries);
      directFlightsStmt.setString(2, originCity);
      directFlightsStmt.setString(3, destinationCity);
      directFlightsStmt.setInt(4, dayOfMonth);

      ResultSet oneHopResults = directFlightsStmt.executeQuery();

      while (oneHopResults.next()) {
        int flightId = oneHopResults.getInt("fid");
        int result_dayOfMonth = oneHopResults.getInt("day_of_month");
        String result_carrierId = oneHopResults.getString("carrier_id");
        String result_flightNum = oneHopResults.getString("flight_num");
        String result_originCity = oneHopResults.getString("origin_city");
        String result_destCity = oneHopResults.getString("dest_city");
        int result_time = oneHopResults.getInt("actual_time");
        int result_capacity = oneHopResults.getInt("capacity");
        int result_price = oneHopResults.getInt("price");

//        sb.append("Day: " + result_dayOfMonth + " Carrier: " + result_carrierId + " Number: "
//                  + result_flightNum + " Origin: " + result_originCity + " Destination: "
//                  + result_destCity + " Duration: " + result_time + " Capacity: " + result_capacity
//                  + " Price: " + result_price + "\n");
        Flight f1 = new Flight(flightId, result_dayOfMonth, result_carrierId, result_flightNum,
                result_originCity, result_destCity, result_time, result_capacity, result_price);
        allFlights.add(new Itinerary(f1, null));
      }

      int directSize = allFlights.size();
      if (!directFlight && numberOfItineraries - directSize > 0) {
//        String unsafeSearch2SQL = "SELECT TOP (" + (numberOfItineraries - directSize) + ") *"
//                + "FROM Flights as f1, Flights as f2" + "WHERE f1.origin_city = \'" + originCity + "\' "
//                + "AND f2.dest_city = \'" + destinationCity + "\' " + "AND f1.dest_city = f2.origin_city"
//                + "AND f1.day_of_month =  " + dayOfMonth + " " + "AND f2.day_of_month =  " + dayOfMonth + "AND f1.canceled != 1 AND f2.canceled != 1"
//                + "ORDER BY f1.actual_time + f2.actual_time ASC";

        //String unsafeSearch3_SQL = "SELECT TOP ? * FROM Flights as f1, Flights as f2 WHERE f1.origin_city = ? AND f2.dest_city = ? AND f1.dest_city = f2.origin_city AND f1.day_of_month = ? AND f2.day_of_month = ? AND f1.canceled != 1 AND f2.canceled != 1 ORDER BY f1.actual_time + f2.actual_time ASC";

        //PreparedStatement unsafeSearch3 = conn.prepareStatement(unsafeSearch3_SQL);
        indirectFlightsStmt.clearParameters();
        indirectFlightsStmt.setInt(1, numberOfItineraries - directSize);
        indirectFlightsStmt.setString(2, originCity);
        indirectFlightsStmt.setString(3, destinationCity);
        indirectFlightsStmt.setInt(4, dayOfMonth);
        indirectFlightsStmt.setInt(5, dayOfMonth);

//        Statement twoFlightSearchStatement = conn.createStatement();
        ResultSet nonDirectResults = indirectFlightsStmt.executeQuery();

        while (nonDirectResults.next()) {
          Flight first = new Flight(nonDirectResults.getInt(1), nonDirectResults.getInt(3),
                  nonDirectResults.getString(5), nonDirectResults.getString(6), nonDirectResults.getString(7),
                  nonDirectResults.getString(9), nonDirectResults.getInt(15), nonDirectResults.getInt(17),
                  nonDirectResults.getInt(18));

          Flight second = new Flight(nonDirectResults.getInt(19), nonDirectResults.getInt(21),
                  nonDirectResults.getString(23), nonDirectResults.getString(24), nonDirectResults.getString(25),
                  nonDirectResults.getString(27), nonDirectResults.getInt(33), nonDirectResults.getInt(35),
                  nonDirectResults.getInt(36));

          allFlights.add(new Itinerary(first, second));
        }
      }

      if (allFlights.size() == 0) {return "No flights match your selection\n";}

//      System.out.println(allFlights.size());

      allFlights.sort(null);
      for (int id = 0; id < allFlights.size(); id++) {allFlights.get(id).id=id;}

      for (int i = 0; i < allFlights.size(); i++) {itineraries.add(allFlights.get(i));}

      while (!allFlights.isEmpty()) {
        Itinerary beginningOfList = allFlights.get(0);
        allFlights.remove(0);
        Flight first = beginningOfList.flight1;
        Flight second = beginningOfList.flight2;
        sb.append("Itinerary " + beginningOfList.id + ": " + beginningOfList.numFlights + " flight(s), " +
                beginningOfList.totalTime + " minutes\n");
        sb.append(first.toString() + "\n");
        if (second != null) {sb.append(second.toString() + "\n");}
      }
      oneHopResults.close();
    } catch (SQLException ex) {
      ex.printStackTrace();
      return "Failed to search\n";
    } catch(Exception e) {
      return "Failed to search\n";
    }

    canBook = true;
    return sb.toString();
  }

  private String flightToString(Flight f) {
    if (f == null) {
      return "";
    } else {
      return "Day: " + f.dayOfMonth + " Carrier: " + f.carrierId + " Number: "
              + f.flightNum + " Origin: " + f.originCity + " Destination: "
              + f.destCity + " Duration: " + f.time + " Capacity: " + f.capacity
              + " Price: " + f.price + "\n";
    }
  }

  /**
   * Implements the book itinerary function.
   *
   * @param itineraryId ID of the itinerary to book. This must be one that is returned by search
   *                    in the current session.
   *
   * @return If the user is not logged in, then return "Cannot book reservations, not logged
   *         in\n". If the user is trying to book an itinerary with an invalid ID or without
   *         having done a search, then return "No such itinerary {@code itineraryId}\n". If the
   *         user already has a reservation on the same day as the one that they are trying to
   *         book now, then return "You cannot book two flights in the same day\n". For all
   *         other errors, return "Booking failed\n".
   *
   *         If booking succeeds, return "Booked flight(s), reservation ID: [reservationId]\n"
   *         where reservationId is a unique number in the reservation system that starts from
   *         1 and increments by 1 each time a successful reservation is made by any user in
   *         the system.
   */
  public String transaction_book(int itineraryId) {
    // TODO: YOUR CODE HERE
    try {
      conn.setAutoCommit(false);
      if (!loggedIn) {
        conn.rollback();
        conn.setAutoCommit(true);
        return "Cannot book reservations, not logged in\n";
      }

      if (!canBook || (itineraryId < 0 || itineraryId >= itineraries.size())) {
        conn.rollback();
        conn.setAutoCommit(true);
        return "No such itinerary " + itineraryId + "\n";
      }

      Itinerary toBook = itineraries.get(itineraryId);

//        Itinerary toBook = null;
//        for (Itinerary possible : itineraries) {
//          if (possible.id == itineraryId) {
//            toBook = possible;
//          }
//        }
//        if (toBook == null) {
//          conn.rollback();
//          conn.setAutoCommit(true);
//          return "No such itinerary " + itineraryId + "\n";
//        }

      if (toBook.flight1.capacity == 0 || (toBook.flight2 != null && toBook.flight2.capacity == 0)) {
        conn.rollback();
        conn.setAutoCommit(true);
        return "Booking failed\n";
      }

      // check whether there's already a reservation on that day
      int day = toBook.flight1.dayOfMonth;
      alreadyHasItineraryStmt.clearParameters();
      alreadyHasItineraryStmt.setString(1, username);
      alreadyHasItineraryStmt.setInt(2, day);

      ResultSet checkAlreadyhas = alreadyHasItineraryStmt.executeQuery();
      checkAlreadyhas.next();
      int alreadyHas = checkAlreadyhas.getInt(1);
      if (alreadyHas != 0) {
        conn.rollback();
        conn.setAutoCommit(true);
        return "You cannot book two flights in the same day\n";
      }

      int alreadyMadeReservations = 0;
      if (toBook.numFlights == 1) {
        reservationCapacityDirectStmt.clearParameters();
        reservationCapacityDirectStmt.setInt(1, toBook.flight1.fid);
        ResultSet cap = reservationCapacityDirectStmt.executeQuery();
        cap.next();
        alreadyMadeReservations = cap.getInt(1);
        if (toBook.flight1.capacity <= alreadyMadeReservations) {
          conn.rollback();
          conn.setAutoCommit(true);
          return "Booking failed\n";
        }
      } else {
        reservationCapacityIndirectStmt.clearParameters();
        reservationCapacityIndirectStmt.setInt(1, toBook.flight1.fid);
        reservationCapacityIndirectStmt.setInt(2, toBook.flight2.fid);
        ResultSet cap = reservationCapacityIndirectStmt.executeQuery();
        cap.next();
        alreadyMadeReservations = cap.getInt(1);
        if (toBook.flight1.capacity <= alreadyMadeReservations || toBook.flight2.capacity <= alreadyMadeReservations) {
          conn.rollback();
          conn.setAutoCommit(true);
          return "Booking failed\n";
        }
      }

      // actually do the reservation
      int price = toBook.flight1.price;
      if (toBook.numFlights == 2) {
        price += toBook.flight2.price;
      }

      ResultSet numReservations = numReservationsStmt.executeQuery();
      numReservations.next();
      int num = numReservations.getInt(1);
      num++;

      updateReservationsStmt.clearParameters();
      updateReservationsStmt.setInt(1, num);
      updateReservationsStmt.setInt(2, 0);
      updateReservationsStmt.setInt(3, toBook.flight1.fid);
      if (toBook.numFlights == 1) {
        updateReservationsStmt.setNull(4, Types.INTEGER);
      } else {
        updateReservationsStmt.setInt(4, toBook.flight2.fid);
      }
      updateReservationsStmt.setString(5, username);
      updateReservationsStmt.setInt(6, price);
      updateReservationsStmt.executeUpdate();

      conn.commit();
      conn.setAutoCommit(true);
      return "Booked flight(s), reservation ID: " + num + "\n";
    } catch (SQLException e) {
      try {
        conn.rollback();
        conn.setAutoCommit(true);
        if (isDeadlock(e)) {
          return transaction_book(itineraryId);
        }
      } catch (SQLException ex ){
        ex.printStackTrace();
        return "Booking failed\n";
      }
    }
    return "Booking failed\n";
  }

  /**
   * Implements the pay function.
   *
   * @param reservationId the reservation to pay for.
   *
   * @return If no user has logged in, then return "Cannot pay, not logged in\n". If the
   *         reservation is not found / not under the logged in user's name, then return
   *         "Cannot find unpaid reservation [reservationId] under user: [username]\n".  If
   *         the user does not have enough money in their account, then return
   *         "User has only [balance] in account but itinerary costs [cost]\n".  For all other
   *         errors, return "Failed to pay for reservation [reservationId]\n"
   *
   *         If successful, return "Paid reservation: [reservationId] remaining balance:
   *         [balance]\n" where [balance] is the remaining balance in the user's account.
   */
  public String transaction_pay(int reservationId) {
    // TODO: YOUR CODE HERE
    try {
      conn.setAutoCommit(false);
      if (!loggedIn) {
        conn.rollback();
        conn.setAutoCommit(true);
        return "Cannot pay, not logged in\n";
      }

      findReservationStmt.clearParameters();
      findReservationStmt.setInt(1, reservationId);
      ResultSet reservation = findReservationStmt.executeQuery();
      if (!reservation.next()) {
        conn.rollback();
        conn.setAutoCommit(true);
        return "Cannot find unpaid reservation " + reservationId + " under user: " + username + "\n";
      }
//      reservation.absolute(1);
      if (!reservation.getString("username").equals(username)) {
        conn.rollback();
        conn.setAutoCommit(true);
        return "Cannot find unpaid reservation " + reservationId + " under user: " + username + "\n";
      }

      int cost = reservation.getInt("cost");

      findUserStmt.clearParameters();
      findUserStmt.setString(1, username);
      ResultSet user = findUserStmt.executeQuery();
      user.next();
      int userBalance = user.getInt("balance");

      if (userBalance < cost) {
        conn.rollback();
        conn.setAutoCommit(true);
        return "User has only " + userBalance + " in account but itinerary costs " + cost + "\n";
      }

      int newBalance = userBalance - cost;
      updateUserBalanceStmt.clearParameters();
      updateUserBalanceStmt.setInt(1, newBalance);
      updateUserBalanceStmt.setString(2, username);
      updateUserBalanceStmt.executeUpdate();

      updateReservationsPaidStmt.clearParameters();
      updateReservationsPaidStmt.setInt(1, reservationId);
      updateReservationsPaidStmt.executeUpdate();

      conn.commit();
      conn.setAutoCommit(true);
      return "Paid reservation: " + reservationId + " remaining balance: " + newBalance + "\n";
    } catch (SQLException e) {
      try {
        conn.rollback();
        conn.setAutoCommit(true);
        if (isDeadlock(e)) {
          return transaction_pay(reservationId);
        }
      } catch (SQLException ex) {
        ex.printStackTrace();
        return "Failed to pay for reservation " + reservationId + "\n";
      }
    }
    return "Failed to pay for reservation " + reservationId + "\n";
  }

  /**
   * Implements the reservations function.
   *
   * @return If no user has logged in, then return "Cannot view reservations, not logged in\n" If
   *         the user has no reservations, then return "No reservations found\n" For all other
   *         errors, return "Failed to retrieve reservations\n"
   *
   *         Otherwise return the reservations in the following format:
   *
   *         Reservation [reservation ID] paid: [true or false]:\n [flight 1 under the
   *         reservation]\n [flight 2 under the reservation]\n Reservation [reservation ID] paid:
   *         [true or false]:\n [flight 1 under the reservation]\n [flight 2 under the
   *         reservation]\n ...
   *
   *         Each flight should be printed using the same format as in the {@code Flight} class.
   *
   * @see Flight#toString()
   */
  public String transaction_reservations() {
    // TODO: YOUR CODE HERE
    try {
      conn.setAutoCommit(false);
      if (!loggedIn) {
        conn.rollback();
        conn.setAutoCommit(true);
        return "Cannot view reservations, not logged in\n";
      }

      StringBuffer sb = new StringBuffer();
      userReservationsStmt.clearParameters();
      userReservationsStmt.setString(1, username.toUpperCase());
      ResultSet userReservations = userReservationsStmt.executeQuery();

      if (!userReservations.next()) {
        conn.rollback();
        conn.setAutoCommit(true);
        return "No reservations found\n";
      }

      sb.append("Reservation " + userReservations.getInt("rid") + " paid: ");
      if (userReservations.getInt("paid") == 1) {
        sb.append("true");
      } else {
        sb.append("false");
      }
      sb.append(":\n");

      int firstFid1 = userReservations.getInt("fid1");
      int firstFid2 = userReservations.getInt("fid2");

      sb.append(flightString(firstFid1) + "\n");
      if (firstFid2 != 0) {
        sb.append(flightString(firstFid2) + "\n");
      }

      while (userReservations.next()) {
        sb.append("Reservation " + userReservations.getInt("rid") + " paid: ");
        if (userReservations.getInt("paid") == 1) {
          sb.append("true");
        } else {
          sb.append("false");
        }
        sb.append(":\n");

        int fid1 = userReservations.getInt("fid1");
        int fid2 = userReservations.getInt("fid2");

        sb.append(flightString(fid1) + "\n");
        if (fid2 != 0) {
          sb.append(flightString(fid2) + "\n");
        }
      }

      conn.commit();
      conn.setAutoCommit(true);
      return sb.toString();
    } catch (SQLException e) {
      try {
        conn.rollback();
        conn.setAutoCommit(true);
        if (isDeadlock(e)) {
          return transaction_reservations();
        }
      } catch (SQLException ex) {
        ex.printStackTrace();
        return "Failed to retrieve reservations\n";
      }
    }
    return "Failed to retrieve reservations\n";
  }

  private String flightString(int fid) {
    try {
      getFlightStmt.clearParameters();
      getFlightStmt.setInt(1, fid);
      ResultSet flight = getFlightStmt.executeQuery();
      if (flight.next()) {
        Flight oneFlight = new Flight(fid, flight.getInt("day_of_month"), flight.getString("carrier_id"),
                "" + flight.getInt("flight_num"), flight.getString("origin_city"), flight.getString("dest_city"),
                flight.getInt("actual_time"), flight.getInt("capacity"), flight.getInt("price"));
        return oneFlight.toString();
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  /**
   * Example utility function that uses prepared statements
   */
  private int checkFlightCapacity(int fid) throws SQLException {
    flightCapacityStmt.clearParameters();
    flightCapacityStmt.setInt(1, fid);

    ResultSet results = flightCapacityStmt.executeQuery();
    results.next();
    int capacity = results.getInt("capacity");
    results.close();

    return capacity;
  }

  /**
   * Utility function to determine whether an error was caused by a deadlock
   */
  private static boolean isDeadlock(SQLException e) {
    return e.getErrorCode() == 1205;
  }

  class Itinerary implements Comparable<Itinerary> {
    public Flight flight1;
    public Flight flight2;
    public int id;

    public int numFlights;
    public int totalTime;



    public Itinerary(Flight first, Flight second) {
      this.flight1 = first;
      this.flight2 = second;
      if (first == null || second == null) {
        this.numFlights = 1;
      } else {this.numFlights = 2;}
      totalTime = 0;
      if (first != null) {totalTime += first.time;}
      if (second != null) {totalTime += second.time;}
    }

    public int compareTo(Itinerary other) {
      int timeDiff = this.totalTime - other.totalTime;
      if (timeDiff != 0) {return timeDiff;}

      return this.flight1.fid - other.flight1.fid;
      // todo: implement tiebreaks
      // todo: make it work for multiple flights
    }
  }

  /**
   * A class to store information about a single flight
   */
  class Flight {
    public int fid;
    public int dayOfMonth;
    public String carrierId;
    public String flightNum;
    public String originCity;
    public String destCity;
    public int time;
    public int capacity;
    public int price;

    Flight(int id, int day, String carrier, String fnum, String origin, String dest, int tm,
           int cap, int pri) {
      fid = id;
      dayOfMonth = day;
      carrierId = carrier;
      flightNum = fnum;
      originCity = origin;
      destCity = dest;
      time = tm;
      capacity = cap;
      price = pri;
    }

    @Override
    public String toString() {
      return "ID: " + fid + " Day: " + dayOfMonth + " Carrier: " + carrierId + " Number: "
              + flightNum + " Origin: " + originCity + " Dest: " + destCity + " Duration: " + time
              + " Capacity: " + capacity + " Price: " + price;
    }
  }
}
