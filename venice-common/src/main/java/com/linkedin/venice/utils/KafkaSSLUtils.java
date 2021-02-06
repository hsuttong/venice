package com.linkedin.venice.utils;

import kafka.server.KafkaConfig;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.protocol.SecurityProtocol;

import java.util.Properties;

import static com.linkedin.venice.ConfigConstants.*;


public class KafkaSSLUtils {
  /**
   * Self-signed cert, expires 2027, use keystore as truststore since self-signed.
   * cert has CN=localhost
   *
   * IMPORTANT NOTE: the "localhost.jks", "localhost.cert", "localhost.key" and "localhost.p12" files only exist
   *                 in the code base; do not try to load this files in actual hosts
   */
  private static final String LOCAL_PASSWORD = "dev_pass";
  private static final String LOCAL_KEYSTORE_JKS = "localhost.jks";

  public static Properties getLocalKafkaBrokerSSlConfig(String host, int port, int sslPort) {
    Properties properties = new Properties();
    properties.put(KafkaConfig.SslProtocolProp(), "TLS");
    //Listen on two ports, one for ssl one for non-ssl
    properties.put(KafkaConfig.ListenersProp(), "PLAINTEXT://" + host + ":" + port + ",SSL://" + host + ":" + sslPort);
    properties.putAll(getLocalCommonKafkaSSLConfig());
    properties.put(SslConfigs.SSL_CONTEXT_PROVIDER_CLASS_CONFIG, DEFAULT_KAFKA_SSL_CONTEXT_PROVIDER_CLASS_NAME);
    return properties;
  }

  public static Properties getLocalCommonKafkaSSLConfig() {
    Properties properties = new Properties();
    String keyStorePath = SslUtils.getPathForResource(LOCAL_KEYSTORE_JKS);
    properties.put(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, keyStorePath);
    properties.put(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, LOCAL_PASSWORD);
    properties.put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, keyStorePath);
    properties.put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, LOCAL_PASSWORD);
    properties.put(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG, "JKS");
    properties.put(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG, "JKS");
    properties.put(SslConfigs.SSL_KEY_PASSWORD_CONFIG, LOCAL_PASSWORD);
    properties.put(SslConfigs.SSL_SECURE_RANDOM_IMPLEMENTATION_CONFIG, "SHA1PRNG");
    properties.put(SslConfigs.SSL_TRUSTMANAGER_ALGORITHM_CONFIG, "SunX509");
    properties.put(SslConfigs.SSL_KEYMANAGER_ALGORITHM_CONFIG, "SunX509");
    return properties;
  }

  public static Properties getLocalKafkaClientSSLConfig() {
    Properties properties = new Properties();
    properties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, SecurityProtocol.SSL.name());
    properties.putAll(getLocalCommonKafkaSSLConfig());
    return properties;
  }

  /**
   * Right now, Venice only supports two Kafka protocols:
   * {@link SecurityProtocol#PLAINTEXT}
   * {@link SecurityProtocol#SSL}
   *
   * @param kafkaProtocol
   * @return
   */
  public static boolean isKafkaProtocolValid(String kafkaProtocol) {
    return kafkaProtocol.equals(SecurityProtocol.PLAINTEXT.name()) || kafkaProtocol.equals(SecurityProtocol.SSL.name());
  }

  public static boolean isKafkaSSLProtocol(String kafkaProtocol) {
    return kafkaProtocol.equals(SecurityProtocol.SSL.name());
  }
}
