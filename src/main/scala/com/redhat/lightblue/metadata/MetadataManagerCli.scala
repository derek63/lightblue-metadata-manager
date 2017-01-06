package com.redhat.lightblue.metadata

import java.nio.file.Files
import java.nio.file.Paths

import scala.io.Source

import org.apache.commons.cli._
import org.apache.commons.cli.HelpFormatter
import org.slf4j._

import com.redhat.lightblue.client.http.LightblueHttpClient
import com.redhat.lightblue.client.http.LightblueHttpClient
import com.redhat.lightblue.metadata.Control._
import com.redhat.lightblue.metadata.MetadataManager._
import com.redhat.lightblue.client.LightblueClient

/**
 * Command Line Interface for {@link MetadataManager}.
 *
 */
class MetadataManagerCli(args: Array[String], _client: scala.Option[LightblueClient]) {

    def this(args: Array[String]) = this(args, None)
    def this(args: String, client: LightblueClient) = this(args.split(" "), Some(client))

    val logger = LoggerFactory.getLogger(MetadataManagerApp.getClass);
    implicit val implicitClient = _client

    val options = new Options();

    try {

        val lbClientOption = Option.builder("lc")
            .desc("Configuration file for lightblue-client. --env is recommended instead.")
            .longOpt("lightblue-client")
            .hasArg()
            .argName("lightblue-client.properties")
            .build();

        val envOption = Option.builder()
            .desc("Lightblue environment (export LB_CLIENT_{ENV}=/home/user/lightblue-clients/lightblue-client-{ENV}.properties")
            .longOpt("env")
            .hasArg()
            .argName("environment, e.g. dev")
            .build();

        val helpOption = Option.builder("h")
            .required(false)
            .desc("Prints usage.")
            .longOpt("help")
            .build();

        val entityOption = Option.builder("e")
            .required(false)
            .longOpt("entity")
            .desc("Entity name. You can use regular expression to match multiple entities by name. You can use $local to match all entities in current local directory).")
            .hasArg()
            .argName("entity name or /regex/ or $local")
            .build();

        val versionOption = Option.builder("v")
            .required(false)
            .longOpt("version")
            .desc("Entity version selector.")
            .hasArg()
            .argName("x.x.x|newest|default")
            .build();

        val pathOption = Option.builder("p")
            .required(false)
            .longOpt("path")
            .desc("Pull specified path, e.g 'entityInfo.indexes'. Leave local metadata otherwise intact.")
            .hasArg()
            .argName("path")
            .build();

        val entityInfoOnlyOption = Option.builder("eio")
            .required(false)
            .longOpt("entityInfoOnly")
            .desc("Push entityInfo only.")
            .build();

        val schemaOnlyOption = Option.builder("so")
            .required(false)
            .longOpt("schemaOnly")
            .desc("Push schema only.")
            .build();

        val setChangelogOption = Option.builder("cl")
            .required(false)
            .longOpt("changelog")
            .desc("Set version.changelog")
            .hasArg()
            .build()

        val setVersionsOption = Option.builder("vs")
            .required(false)
            .longOpt("versions")
            .desc("Set schema version.value and entityInfo.defaultVersion")
            .hasArg()
            .build()

        // options which apply to any operation
        options.addOption(helpOption)

        if (args.length == 0) {
            printUsage(options)
            System.exit(1)
        }

        val operation = args(0)

        // operation specific options
        operation match {
             case "list" => {
                options.addOption(lbClientOption)
                options.addOption(envOption)
            }
            case "pull" => {
                options.addOption(lbClientOption)
                options.addOption(envOption)
                options.addOption(entityOption)
                options.addOption(versionOption)
                options.addOption(pathOption)
            }
            case "push" => {
                options.addOption(lbClientOption)
                options.addOption(envOption)
                options.addOption(entityOption)
                options.addOption(entityInfoOnlyOption)
                options.addOption(schemaOnlyOption)
            }
            case "diff" => {
                options.addOption(lbClientOption)
                options.addOption(envOption)
                options.addOption(entityOption)
            }
            case "set" => {
                options.addOption(entityOption)
                options.addOption(setVersionsOption)
                options.addOption(setChangelogOption)
            }
            case _ => ;
        }

        val optionsArgs = args.slice(1, args.length)

        val parser = new DefaultParser()
        implicit val cmd = parser.parse(options, optionsArgs)

        if (cmd.hasOption('h')) {
            printUsage(options)
            System.exit(0);
        }

        if (!List("push", "pull", "diff", "list", "set").contains(operation)) {
            throw new ParseException(s"""Unsupported operation $operation""")
        }

        if (cmd.hasOption("lc") && cmd.hasOption("env")) {
            throw new ParseException("Either -lc or --env is required");
        }

        operation match {
            case "list" => {
                metadataManager.listEntities().foreach(println(_))
            }
            case "pull" => {

                if (!cmd.hasOption("e")) {
                    throw new MissingArgumentException("-e <entity name> is required")
                }

                if (!cmd.hasOption("v")) {
                    throw new MissingArgumentException("-v <entity version> is required")
                }

                val version = parseVersion(cmd.getOptionValue("v"))
                val entityNameValue = cmd.getOptionValue("e")

                val remoteEntities = if (entityNameValue == "$local") {
                    // -e $local means that all local entities are to be pulled from Lightblue (refresh)
                    metadataManager.getEntities(localEntityNames(), version)
                } else {
                    // entityNameValue could be a single entity name or pattern
                    metadataManager.getEntities(entityNameValue, version)
                }

                remoteEntities foreach { remoteEntity =>
                    if (cmd.hasOption("p")) {
                        // download metadata path from Lightblue and save it locally
                        val path = cmd.getOptionValue("p")

                        val localEntity = new Entity(using(Source.fromFile(s"""${remoteEntity.name}.json""")) { source =>
                            source.mkString
                        })

                        val updatedLocalEntity = localEntity.replacePath(path, remoteEntity)

                        println(s"""Saving $path to ${updatedLocalEntity.name}.json...""")
                        Files.write(Paths.get(s"""${updatedLocalEntity.name}.json"""), updatedLocalEntity.text.getBytes)
                    } else {
                        // download metadata from Lightblue and save it locally
                        println(s"""Saving ${remoteEntity}...""")
                        Files.write(Paths.get(s"""${remoteEntity.name}.json"""), remoteEntity.text.getBytes)
                    }
                }
            }
            case "diff" => {
                if (!cmd.hasOption("e")) {
                    throw new MissingArgumentException("-e <entity name> is required")
                }

                val entityName = cmd.getOptionValue("e")

                val metadata = using(Source.fromFile(s"""$entityName.json""")) { source =>
                    source.mkString
                }

                var entity = new Entity(metadata)

                metadataManager.diffEntity(entity)
            }
            case "push" => {
                if (!cmd.hasOption("e")) {
                    throw new MissingArgumentException("-e <entity name> is required")
                }

                if (cmd.hasOption("eio") && cmd.hasOption("so")) {
                    throw new ParseException("You need to provide either --entityInfoOnly or --schemaOnly switches, not both")
                }

                val entityNameValue = cmd.getOptionValue("e")

                // -e $local means that all local files are to be pulled
                val entityNames = if (entityNameValue == "$local") {
                    println(localEntityNames())
                    localEntityNames()
                } else {
                    List(entityNameValue)
                }

                for (entityName <- entityNames) {

                    val metadata = using(Source.fromFile(s"""$entityName.json""")) { source =>
                        source.mkString
                    }

                    var entity = new Entity(metadata)

                    logger.debug(s"""Loaded $entity from local file""")

                    if (cmd.hasOption("eio")) {
                        metadataManager.putEntity(entity, MetadataScope.ENTITYINFO)
                    } else if (cmd.hasOption("so")) {
                        metadataManager.putEntity(entity, MetadataScope.SCHEMA)
                    } else {
                        metadataManager.putEntity(entity, MetadataScope.BOTH)
                    }
                }

            }
            case "set" => {
                if (!cmd.hasOption("e")) {
                    throw new MissingArgumentException("-e <entity name> is required")
                }

                val entityName = cmd.getOptionValue("e")

                val metadata = using(Source.fromFile(s"""$entityName.json""")) { source =>
                    source.mkString
                }

                var entity = new Entity(metadata)

                if (cmd.hasOption("vs")) {
                    entity = entity.version(cmd.getOptionValue("vs"))
                }

                if (cmd.hasOption("cl")) {
                    entity = entity.changelog(cmd.getOptionValue("cl"))
                }

                Files.write(Paths.get(s"""${entityName}.json"""), entity.text.getBytes)
            }
            case other => throw new UnsupportedOperationException(s"""Unknown operation $other""")
        }

    } catch {
        case pe: ParseException => {
            logger.error(pe.getMessage)
            printUsage(options)
            System.exit(1);
        }
    }

    // return *.json file names from current directory
    def localEntityNames(): List[String] = {
        new java.io.File(".").listFiles.filter(f => { f.isFile() && f.getName.endsWith("json") && !f.getName.startsWith(".") }).map { f => f.getName.replaceAll("""\.json""", "") }.toList
    }

    def printUsage(options: Options) {
        val formatter = new HelpFormatter();
        formatter.printHelp(180, MetadataManagerApp.getClass.getSimpleName + " <operation> <options>",
            "\nAvailable operations: list, pull, push, diff and set. Add -h after operation to see options it accepts.\n\nOptions:", options, null)

    }

    def parseVersion(version: String): List[EntityVersion] => scala.Option[EntityVersion] = {
        version match {
            case "default" => MetadataManager.entityVersionDefault
            case "newest"  => MetadataManager.entityVersionNewest
            case x         => MetadataManager.entityVersionExplicit(x)
        }
    }

    /**
     * Initialize Lightblue client from cli. Use explicitly passed client if provided (for unit tests).
     *
     */
    def createClient(cmd: CommandLine, client: scala.Option[LightblueClient]): scala.Option[LightblueClient] = {
        client match {
            case None => {

                if (!cmd.hasOption("lc") && !cmd.hasOption("env")) {
                    None
                } else {
                    val lbClientFilePath = if (cmd.hasOption("lc")) cmd.getOptionValue("lc") else {
                        val envVarName = "LB_CLIENT_" + cmd.getOptionValue("env").toUpperCase()
                        System.getenv(envVarName) match {
                            case null => throw new ParseException(s"""${envVarName} is not set!""")
                            case x    => x
                        }
                    }

                    logger.debug(s"""Reading lightblue client configuration from ${lbClientFilePath}""")
                    Some(new LightblueHttpClient(lbClientFilePath))
                }
            }
            case Some(x) => {
                logger.debug("""Lightblue client passed to cli.""")
                Some(x)
            }
        }
    }

    /**
     * create metadata manager
     */
    def metadataManager(implicit cmd: CommandLine, client: scala.Option[LightblueClient]): MetadataManager = {
        createClient(cmd, client) match {
            case None => throw new Exception("Lightblue client is needed to create MetadataManager!")
            case Some(x) => new MetadataManager(x)
        }
    }

}