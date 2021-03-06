package com.twl.xg.service;

import com.twl.xg.dao.BorderRouterRepository;
import com.twl.xg.dao.SensorDataRepository;
import com.twl.xg.dao.SensorRepository;
import com.twl.xg.domain.*;
import org.apache.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.regex.Pattern;

/**
 * This is the service layer class that fetches data from repositories.
 *
 * @see BorderRouterRepository
 * @see SensorRepository
 * @see SensorDataRepository
 * @author Xiaozheng Guo
 * @version 1.0
 */
@Service
@SuppressWarnings("SpringJavaAutowiredFieldsWarningInspection")
public class DataFetchingAndMappingService {
  @Autowired
  private ApplicationContext context;
  @Autowired
  private SensorDataRepository sensorDataRepository;
  @Autowired
  private SensorRepository sensorRepository;
  @Autowired
  private BorderRouterRepository borderRouterRepository;
  @Autowired
  private PropertyService propertyService;

  private static final Logger logger = Logger.getLogger(DataFetchingAndMappingService.class);

  /**
   * Update <code>dataTypeList</code> according to the input array. Also update
   * the "dataTypeListString" property in database.
   *
   * ALl data type string must match this pattern: <code> [0-9a-zA-Z]+ </code>;
   *
   * Every time the <code>dataTypeList</code> gets updated, the <code>sensor_data</code>
   * table in database will be cleared. Because the number of data fields have been
   * changed, and it will be inconsistent.
   *
   * This method need to be synchronized to avoid race condition when this method
   * is called in multi-threads.
   *
   * Return value is the current <code>dataTypeList</code>, return <code>null</code>
   * if the input is invalid.
   *
   * After this initialize operation, the <code>currentDataTypeList</code> is sorted
   * in lexicographic order.
   *
   * @throws RuntimeException if the input dataTypeArray contains invalid characters.
   * @param dataTypeArray An array of data types string
   * @return Return a list of String which contains all data types name set by user.
   */
  @Transactional
  public List<String> setDataTypes(String[] dataTypeArray) {
    synchronized (DataFetchingAndMappingService.class) {
      // check if the input string array is empty
      if (dataTypeArray == null || dataTypeArray.length == 0) {
        // do nothing
        logger.error("setDataTypes: empty input");
        return null;
      }
      // check invalid input
      for (String str : dataTypeArray) {
        if (!Pattern.matches("[0-9a-zA-Z]+", str)) {
          logger.error("setDataTypes:  Invalid input dataType string --> " + str);
          throw new RuntimeException("Invalid input dataType string --> " + str);
        }
      }

      // check if the input data types are the same to the current data types.
      List<String> currentDataTypeList = (List<String>) context.getBean("dataTypeList");
      if (allSameElements(currentDataTypeList, Arrays.asList(dataTypeArray))) {
        // if same, do nothing, return current dataTypeList
        logger.debug("setDataTypes: Input data types are the same to current data types");
        return currentDataTypeList;
      } else {
        // else, clear sensor_data table, update currentDataTypeList and
        // dataTypeListString property in database, and return the new list
        sensorDataRepository.clear();
        currentDataTypeList.clear();
        currentDataTypeList.addAll(new HashSet<String>(Arrays.asList(dataTypeArray))); // deduplicate
        Collections.sort(currentDataTypeList);
        logger.debug("setDataTypes: dataTypeList updated to: " + currentDataTypeList.toString());

        StringBuilder sb = new StringBuilder();
        for (String dataType : currentDataTypeList) {
          sb.append(dataType);
          sb.append(",");
        }
        sb.deleteCharAt(sb.length() - 1);
        propertyService.setProperty("dataTypeListString", sb.toString());

        return currentDataTypeList;
      }
    }
  }

  /**
   * Check if the input string lists' elements are the same to each other without
   * considering the order.
   */
  private boolean allSameElements(List<String> lst1, List<String> lst2) {
    Set<String> set1 = new HashSet<>(lst1);
    Set<String> set2 = new HashSet<>(lst2);
    return set1.equals(set2);
  }

  /**
   * Get the total number of border router in database.
   *
   * @return the number of border router.
   */
  @Transactional
  public long getBorderRouterNumber() {
    return borderRouterRepository.size();
  }

  /**
   * Update the name of a sensor.
   *
   * @param sensorIp IP of the sensor you want to update.
   * @param sensorName The name you want to update for the sensor.
   * @return <code>true</code> for success, <code>false</code> otherwise.
   */
  @Transactional
  public boolean updateSensorName(String sensorIp, String sensorName) {
    return sensorRepository.updateSensorName(sensorIp, sensorName);
  }

  /**
   * Get IP and name of all border router, if there is no border router in database,
   * return an empty list.
   *
   * @return a list of String array that contains "IP, name" pair.
   */
  @Transactional
  public List<String[]> getAllBorderRouterIpAndName() {
    List<String[]> ret = new ArrayList<>();
    List<BorderRouterEntity> borderRouterList = borderRouterRepository.getAll();
    for (BorderRouterEntity borderRouter : borderRouterList) {
      String[] pair = new String[] {borderRouter.getBorderRouterIp(), borderRouter.getBorderRouterName()};
      ret.add(pair);
    }
    logger.debug("getAllBorderRouterIpAndName:  List size = " + ret.size());
    return ret;
  }

  /**
   * Fetching data from database for given border router. Return all the data that
   * generated later than the given timeStamp;
   *
   * If the input time stamp is <code>null</code>, return all data for the router.
   *
   * Return <code>null</code> if the input border router IP doesn't exist in the
   * database or the size of the results is 0.
   *
   * @param borderRouterIp The IPv6 address of the border router you want to
   *                       fetch date from.
   * @param timeStamp      The earliest time stamp.
   * @return An instance of <code>BorderRouterWrapper</code> that contains a list
   * of <code>SensorDataEntity</code>.
   */
  @Transactional
  public BorderRouterWrapper getDataForBorderRouterFromDB(String borderRouterIp, Date timeStamp) {
    int dataCount = 0;

    // get borderRouter entity first
    BorderRouterEntity borderRouter = borderRouterRepository.get(borderRouterIp);
    if (borderRouter == null) {
      logger.error("getDataForBorderRouterFromDB:  The input border router IP doesn't exist, ip = " + borderRouterIp);
      return null;
    }
    // get all sensors connected to that border router
    List<SensorEntity> sensorList = sensorRepository.getAll(borderRouterIp);
    if (sensorList.isEmpty()) {
      logger.debug("getDataForBorderRouterFromDB:  No sensor connected to the border router, ip = " + borderRouterIp);
      return null;
    }
    // for each sensor, get its SensorWrapper, store them in list
    List<SensorWrapper> sensorWrapperList = new ArrayList<>();
    for (SensorEntity sensor : sensorList) {
      SensorWrapper sensorWrapper= getDataForSensorFromDB(sensor.getSensorIp(), timeStamp);
      if (sensorWrapper != null) {
        dataCount += sensorWrapper.getDataList().size();
        sensorWrapperList.add(sensorWrapper);
      }
    }
    if (sensorWrapperList.isEmpty()) {
      logger.debug("getDataForBorderRouterFromDB:  No data found for border router IP = " + borderRouterIp);
      return null;
    }
    // create BorderRouterWrapper
    BorderRouterWrapper borderRouterWrapper = new BorderRouterWrapper(borderRouter);
    borderRouterWrapper.setSensorWrapperList(sensorWrapperList);
    logger.debug("getDataForBorderRouterFromDB:  " + dataCount + " data entries found for border router IP = " + borderRouterIp);
    return borderRouterWrapper;
  }

  /**
   * Fetching data generated after the given time stamp for the given sensor from
   * database.
   *
   * Return all data if the input time stamp is <code>null</code>.
   *
   * Return <code>null</code> if the result size == 0.
   *
   * Return <code>null</code> if the input sensorIp doesn't exist in database.
   *
   * @param sensorIp  he IPv6 address of the sensor you want to fetch from.
   * @param timeStamp The earliest time stamp.
   * @return An instance of <code>SensorWrapper</code> which contains a list of
   * <code>SensorDataEntity</code>.
   */
  @Transactional
  public SensorWrapper getDataForSensorFromDB(String sensorIp, Date timeStamp) {
    SensorEntity sensorEntity = sensorRepository.get(sensorIp);
    if (sensorEntity == null) {
      logger.error("getDataForSensorFromDB:  The input sensor IP is not exists");
      return null;
    }
    List<SensorDataEntity> dataList;
    if (timeStamp == null) {
      dataList = sensorDataRepository.getAll(sensorIp);
    } else {
      dataList = sensorDataRepository.getAllLaterThan(sensorIp, timeStamp);
    }
    if (dataList.isEmpty()) {
      logger.debug("getDataForSensorFromDB:  result size == 0");
      return null;
    }
    SensorWrapper sensorWrapper = new SensorWrapper(sensorEntity);
    sensorWrapper.setDataList(dataList);
    logger.debug("getDataForSensorFromDB:  Get data entries for sensor IP = " + sensorIp + " after " + timeStamp);
    for (SensorDataEntity data : sensorWrapper.getDataList()) {
      logger.debug("getDataForSensorFromDB:  data entity: " + data.toString());
    }
    return sensorWrapper;
  }

  /**
   * Fetching all data generated after the given time stamp from database. If the
   * input time stamp is <code>null</code>, return all data.
   *
   * Return <code>null</code> if the size of result is 0.
   *
   * @param timeStamp The earliest time stamp.
   * @return An instance of <code>DataPackage</code> which contains all data.
   */
  @Transactional
  public DataPackage getAllDataFromDB(Date timeStamp) {
    // get all border router first
    List<BorderRouterEntity> borderRouterList = borderRouterRepository.getAll();
    // for each border router, get its BorderRouterWrapper, store them in list
    List<BorderRouterWrapper> borderRouterWrapperList = new ArrayList<>();
    for (BorderRouterEntity borderRouter : borderRouterList) {
      BorderRouterWrapper borderRouterWrapper = getDataForBorderRouterFromDB(borderRouter.getBorderRouterIp(), timeStamp);
      if (borderRouterWrapper != null) {
        borderRouterWrapperList.add(borderRouterWrapper);
      }
    }
    if (borderRouterWrapperList.isEmpty()) {
      logger.debug("getAllDataFromDB:  No data found after timeStamp = " + timeStamp);
      return null;
    } else {
      DataPackage dataPackage = new DataPackage(borderRouterWrapperList);
      logger.debug("getAllDataFromDB:  The total number of data entries is: " + dataPackage.getSize());
      return dataPackage;
    }
  }

  /**
   * Delete all entries in database, basically we just need to clear border_router table
   */
  @Transactional
  public void clearDataBase() {
    borderRouterRepository.clear();
  }

  /**
   * Delete all sensor data entries in database. After this operation, ensure there
   * is no sensor data entry in database.
   *
   * @return the number of entries have been deleted.
   */
  @Transactional
  public int clearSensorData() {
    return sensorDataRepository.clear();
  }

  /**
   * Get all sensor IP in database, if there is no sensor in database, return an
   * empty list.
   *
   * @return a list of sensor IP
   */
  @Transactional
  public List<String> getAllSensorIp() {
    return sensorRepository.getAllSensorIp();
  }

  /**
   * Get all sensor IP that connected to the given border router IP. If there is no sensor
   * connected to the border router or the input borderRouterIP doesn't exist, return an
   * empty list;
   *
   * @return A list of sensor IP
   */
  @Transactional
  public List<String> getSensorIpByBorderRouterIp(String borderRouterIp) {
    List<SensorEntity> sensorEntityList = sensorRepository.getAll(borderRouterIp);
    List<String> sensorIpList = new ArrayList<>();
    for (SensorEntity sensorEntity : sensorEntityList) {
      if (sensorEntity.getSensorIp() != null) {
        sensorIpList.add(sensorEntity.getSensorIp());
      }
    }
    return sensorIpList;
  }

  /**
   * Get border router entity for given border router IP.
   * Return null if the input border router IP doesn't exist in database.
   *
   * @param borderRouterIP
   * @return An instance of <code>BorderRouterEntity</code>
   */
  @Transactional
  public BorderRouterEntity getBorderRouterEntity(String borderRouterIP) {
    logger.debug("getBorderRouterEntity:  get border router entity for IP = " + borderRouterIP);
    return borderRouterRepository.get(borderRouterIP);
  }
}
