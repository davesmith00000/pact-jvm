package au.com.dius.pact.provider

import au.com.dius.pact.model.PactReader
import au.com.dius.pact.model.Response
import au.com.dius.pact.model.v3.V3Pact
import au.com.dius.pact.model.v3.messaging.Message
import au.com.dius.pact.model.v3.messaging.MessagePact
import groovy.util.logging.Slf4j
import org.apache.commons.lang3.StringUtils
import org.fusesource.jansi.Ansi
import org.fusesource.jansi.AnsiConsole
import org.reflections.Reflections
import org.reflections.scanners.MethodAnnotationsScanner
import org.reflections.util.ConfigurationBuilder
import org.reflections.util.FilterBuilder
@SuppressWarnings('UnusedImport')
import scala.collection.JavaConverters$

import java.lang.reflect.Method

/**
 * Verifies the providers against the defined consumers in the context of a build plugin
 */
@SuppressWarnings('DuplicateStringLiteral')
@Slf4j
class ProviderVerifier {

  static final String PACT_FILTER_CONSUMERS = 'pact.filter.consumers'
  static final String PACT_FILTER_DESCRIPTION = 'pact.filter.description'
  static final String PACT_FILTER_PROVIDERSTATE = 'pact.filter.providerState'

  Closure projectHasProperty = { }
  Closure projectGetProperty = { }
  def pactLoadFailureMessage
  Closure isBuildSpecificTask = { }
  Closure executeBuildSpecificTask = { }
  Closure projectClasspath = { }

  Map verifyProvider(ProviderInfo provider) {
    Map failures = [:]

    def consumers = provider.consumers.findAll(this.&filterConsumers)
    if (consumers.empty) {
      AnsiConsole.out().println(Ansi.ansi().a('         ').fg(Ansi.Color.YELLOW)
        .a('WARNING: There are no consumers to verify').reset())
    }
    consumers.each(this.&runVerificationForConsumer.curry(failures, provider))

    failures
  }

  void runVerificationForConsumer(Map failures, ProviderInfo provider, ConsumerInfo consumer) {
    AnsiConsole.out().println(Ansi.ansi().a('\nVerifying a pact between ').bold().a(consumer.name)
      .boldOff().a(' and ').bold().a(provider.name).boldOff())

    def pact = loadPactFileForConsumer(consumer)
    forEachInteraction(pact, this.&verifyInteraction.curry(provider, consumer, pact, failures))
  }

  List interactions(def pact) {
    if (pact instanceof MessagePact) {
      pact.messages.findAll(this.&filterInteractions)
    } else {
      pact.interactions.findAll(this.&filterInteractions)
    }
  }

  void forEachInteraction(def pact, Closure verifyInteraction) {
    List interactions = interactions(pact)
    if (interactions.empty) {
      AnsiConsole.out().println(Ansi.ansi().a('         ').fg(Ansi.Color.YELLOW)
        .a('WARNING: Pact file has no interactions')
        .reset())
    } else {
      interactions.each(verifyInteraction)
    }
  }

  @SuppressWarnings('ThrowRuntimeException')
  def loadPactFileForConsumer(ConsumerInfo consumer) {
    if (consumer.pactFile instanceof URL) {
      AnsiConsole.out().println(Ansi.ansi().a("  [from URL ${consumer.pactFile}]"))
      def options = [:]
      if (consumer.pactFileAuthentication) {
        options.authentication = consumer.pactFileAuthentication
      }
      PactReader.loadPact(options, consumer.pactFile)
    } else if (consumer.pactFile instanceof File || pactFileExists(consumer.pactFile)) {
      AnsiConsole.out().println(Ansi.ansi().a("  [Using file ${consumer.pactFile}]"))
      PactReader.loadPact(consumer.pactFile)
    } else {
      throw new RuntimeException(pactLoadFailureMessage instanceof Closure ? pactLoadFailureMessage.call(consumer) :
        pactLoadFailureMessage as String)
    }
  }

  private static boolean pactFileExists(def pactFile) {
    pactFile && new File(pactFile).exists()
  }

  boolean filterConsumers(def consumer) {
    !projectHasProperty(PACT_FILTER_CONSUMERS) ||
      consumer.name in projectGetProperty(PACT_FILTER_CONSUMERS).split(',')*.trim()
  }

  boolean filterInteractions(def interaction) {
    if (projectHasProperty(PACT_FILTER_DESCRIPTION) && projectHasProperty(PACT_FILTER_PROVIDERSTATE)) {
      matchDescription(interaction) && matchState(interaction)
    } else if (projectHasProperty(PACT_FILTER_DESCRIPTION)) {
      matchDescription(interaction)
    } else if (projectHasProperty(PACT_FILTER_PROVIDERSTATE)) {
      matchState(interaction)
    } else {
      true
    }
  }

  private boolean matchState(interaction) {
    if (interaction.providerState) {
      interaction.providerState ==~ projectGetProperty(PACT_FILTER_PROVIDERSTATE)
    } else {
      projectGetProperty(PACT_FILTER_PROVIDERSTATE).empty
    }
  }

  private boolean matchDescription(interaction) {
    interaction.description ==~ projectGetProperty(PACT_FILTER_DESCRIPTION)
  }

  void verifyInteraction(ProviderInfo provider, ConsumerInfo consumer, def pact, Map failures, def interaction) {
    def interactionMessage = "Verifying a pact between ${consumer.name} and ${provider.name}" +
      " - ${interaction.description}"

    def stateChangeOk = true
    if (interaction.providerState) {
      stateChangeOk = stateChange(interaction.providerState, provider, consumer)
      log.debug "State Change: \"${interaction.providerState}\" -> ${stateChangeOk}"
      if (stateChangeOk != true) {
        failures[interactionMessage] = stateChangeOk
        stateChangeOk = false
      } else {
        interactionMessage += " Given ${interaction.providerState}"
      }
    }

    if (stateChangeOk) {
      AnsiConsole.out().println(Ansi.ansi().a('  ').a(interaction.description))

      if (verificationType(provider, consumer) == PactVerification.REQUST_RESPONSE) {
        log.debug('Verifying via request/response')
        verifyResponseFromProvider(provider, interaction, interactionMessage, failures)
      } else {
        log.debug('Verifying via annotated test method')
        verifyResponseByInvokingProviderMethods(pact, provider, consumer, interaction, interactionMessage, failures)
      }
    }
  }

  private static PactVerification verificationType(ProviderInfo provider, ConsumerInfo consumer) {
    consumer.verificationType ?: provider.verificationType
  }

  @SuppressWarnings('PrintStackTrace')
  def stateChange(String state, ProviderInfo provider, ConsumerInfo consumer) {
    AnsiConsole.out().println(Ansi.ansi().a('  Given ').bold().a(state).boldOff())
    try {
      def stateChangeHandler = consumer.stateChange
      def stateChangeUsesBody = consumer.stateChangeUsesBody
      if (stateChangeHandler == null) {
        stateChangeHandler = provider.stateChangeUrl
        stateChangeUsesBody = provider.stateChangeUsesBody
      }
      if (stateChangeHandler == null || (stateChangeHandler instanceof String
        && StringUtils.isBlank(stateChangeHandler))) {
        AnsiConsole.out().println(Ansi.ansi().a('         ').fg(Ansi.Color.YELLOW)
          .a('WARNING: State Change ignored as there is no stateChange URL')
          .reset())
        return true
      } else if (stateChangeHandler instanceof Closure) {
        def result = stateChangeHandler.call(state)
        log.debug "Invoked state change closure -> ${result}"
        if (!(result instanceof URL)) {
          return result
        }
        stateChangeHandler = result
      } else if (isBuildSpecificTask(stateChangeHandler)) {
        log.debug "Invokeing build specific task ${stateChangeHandler}"
        executeBuildSpecificTask(stateChangeHandler, state)
        return true
      }
      return executeHttpStateChangeRequest(stateChangeHandler, stateChangeUsesBody, state, provider)
    } catch (e) {
      AnsiConsole.out().println(Ansi.ansi().a('         ').fg(Ansi.Color.RED).a('State Change Request Failed - ')
        .a(e.message).reset())
      if (projectHasProperty('pact.showStacktrace')) {
        e.printStackTrace()
      }
      return e
    }
  }

  private executeHttpStateChangeRequest(stateChangeHandler, useBody, String state, ProviderInfo provider) {
    try {
      def url = stateChangeHandler instanceof URI ? stateChangeHandler
        : new URI(stateChangeHandler.toString())
      ProviderClient client = new ProviderClient(provider: provider)
      def response = client.makeStateChangeRequest(url, state, useBody)
      log.debug "Invoked state change $url -> ${response?.statusLine}"
      if (response) {
        try {
          if (response.statusLine.statusCode >= 400) {
            AnsiConsole.out().println(Ansi.ansi().a('         ').fg(Ansi.Color.RED)
              .a('State Change Request Failed - ')
              .a(response.statusLine.toString()).reset())
            return 'State Change Request Failed - ' + response.statusLine.toString()
          }
        } finally {
          response.close()
        }
      }
    } catch (URISyntaxException ex) {
      AnsiConsole.out().println(Ansi.ansi().a('         ').fg(Ansi.Color.YELLOW)
        .a("WARNING: State Change ignored as there is no stateChange URL, received \"$stateChangeHandler\"")
        .reset())
    }
    true
  }

  @SuppressWarnings('PrintStackTrace')
  void verifyResponseFromProvider(ProviderInfo provider, def interaction, String interactionMessage, Map failures) {
    try {
      ProviderClient client = new ProviderClient(request: interaction.request, provider: provider)

      def expectedResponse = interaction.response
      def actualResponse = client.makeRequest()

      verifyRequestResponsePact(expectedResponse, actualResponse, interactionMessage, failures)
    } catch (e) {
      AnsiConsole.out().println(Ansi.ansi().a('      ').fg(Ansi.Color.RED).a('Request Failed - ')
        .a(e.message).reset())
      failures[interactionMessage] = e
      if (projectHasProperty('pact.showStacktrace')) {
        e.printStackTrace()
      }
    }
  }

  void verifyRequestResponsePact(Response expectedResponse, Map actualResponse, String interactionMessage,
                                 Map failures) {
    def comparison = ResponseComparison.compareResponse(expectedResponse, actualResponse,
      actualResponse.statusCode, actualResponse.headers, actualResponse.data)

    AnsiConsole.out().println('    returns a response which')

    def s = ' returns a response which'
    displayMethodResult(failures, expectedResponse.status, comparison.method, interactionMessage + s)
    displayHeadersResult(failures, expectedResponse.headers, comparison.headers, interactionMessage + s)
    displayBodyResult(failures, comparison.body, interactionMessage + s)
  }

  void displayMethodResult(Map failures, int status, def comparison, String comparisonDescription) {
    def ansi = Ansi.ansi().a('      ').a('has status code ').bold().a(status).boldOff().a(' (')
    if (comparison == true) {
      AnsiConsole.out().println(ansi.fg(Ansi.Color.GREEN).a('OK').reset().a(')'))
    } else {
      AnsiConsole.out().println(ansi.fg(Ansi.Color.RED).a('FAILED').reset().a(')'))
      failures["$comparisonDescription has status code $status"] = comparison
    }
  }

  void displayHeadersResult(Map failures, def expected, Map comparison, String comparisonDescription) {
    if (!comparison.isEmpty()) {
      AnsiConsole.out().println('      includes headers')
      Map expectedHeaders = expected
      comparison.each { key, headerComparison ->
        def expectedHeaderValue = expectedHeaders[key]
        def ansi = Ansi.ansi().a('        "').bold().a(key).boldOff().a('" with value "').bold()
          .a(expectedHeaderValue).boldOff().a('" (')
        if (headerComparison == true) {
          AnsiConsole.out().println(ansi.fg(Ansi.Color.GREEN).a('OK').reset().a(')'))
        } else {
          AnsiConsole.out().println(ansi.fg(Ansi.Color.RED).a('FAILED').reset().a(')'))
          failures["$comparisonDescription includes headers \"$key\" with value \"$expectedHeaderValue\""] =
            headerComparison
        }
      }
    }
  }

  void displayBodyResult(Map failures, def comparison, String comparisonDescription) {
    def ansi = Ansi.ansi().a('      ').a('has a matching body').a(' (')
    if (comparison.isEmpty()) {
      AnsiConsole.out().println(ansi.fg(Ansi.Color.GREEN).a('OK').reset().a(')'))
    } else {
      AnsiConsole.out().println(ansi.fg(Ansi.Color.RED).a('FAILED').reset().a(')'))
      failures["$comparisonDescription has a matching body"] = comparison
    }
  }

  @SuppressWarnings(['PrintStackTrace', 'ThrowRuntimeException', 'ParameterCount'])
  void verifyResponseByInvokingProviderMethods(def pact, ProviderInfo providerInfo, ConsumerInfo consumer,
                                               def interaction, String interactionMessage,
                                               Map failures) {
    try {
      def urls = projectClasspath()
      URLClassLoader loader = new URLClassLoader(urls, GroovyObject.classLoader)
      def configurationBuilder = new ConfigurationBuilder()
        .setScanners(new MethodAnnotationsScanner())
        .addClassLoader(loader)
        .addUrls(loader.URLs)

      def scan = packagesToScan(providerInfo, consumer)
      if (!scan.empty) {
        def filterBuilder = new FilterBuilder()
        scan.each { filterBuilder.include(it) }
        configurationBuilder.filterInputsBy(filterBuilder)
      }

      Reflections reflections = new Reflections(configurationBuilder)
      def methodsAnnotatedWith = reflections.getMethodsAnnotatedWith(PactVerifyProvider)
      def providerMethods = methodsAnnotatedWith.findAll { Method m ->
        log.debug("Found annotated method $m")
        def annotation = m.annotations.find { it.annotationType().toString() == PactVerifyProvider.toString() }
        log.debug("Found annotation $annotation")
        annotation?.value() == interaction.description
      }

      if (providerMethods.empty) {
        throw new RuntimeException('No annotated methods were found for interaction ' +
          "'${interaction.description}'")
      } else {
        if (pact instanceof MessagePact) {
          verifyMessagePact(providerMethods, interaction as Message, interactionMessage, failures)
        } else {
          def expectedResponse = interaction.response
          providerMethods.each {
            def actualResponse = invokeProviderMethod(it)
            verifyRequestResponsePact(expectedResponse, actualResponse, interactionMessage, failures)
          }
        }
      }
    } catch (e) {
      AnsiConsole.out().println(Ansi.ansi().a('      ').fg(Ansi.Color.RED).a('Verification Failed - ')
        .a(e.message).reset())
      failures[interactionMessage] = e
      if (projectHasProperty('pact.showStacktrace')) {
        e.printStackTrace()
      }
    }
  }

  private List packagesToScan(ProviderInfo providerInfo, ConsumerInfo consumer) {
    consumer.packagesToScan ?: providerInfo.packagesToScan
  }

  void verifyMessagePact(Set methods, Message message, String interactionMessage, Map failures) {
    methods.each {
      AnsiConsole.out().println('    generates a message which')
      def actualMessage = invokeProviderMethod(it)
      def comparison = ResponseComparison.compareMessage(message, actualMessage)
      def s = ' generates a message which'
      displayBodyResult(failures, comparison, interactionMessage + s)
    }
  }

  def invokeProviderMethod(Method m) {
    m.invoke(m.declaringClass.newInstance())
  }

  void displayFailures(failures) {
    AnsiConsole.out().println('\nFailures:\n')
    failures.eachWithIndex { err, i ->
      AnsiConsole.out().println("$i) ${err.key}")
      if (err.value instanceof Throwable) {
        displayError(err.value)
      } else if (err.value instanceof Map && err.value.containsKey('diff')) {
        displayDiff(err)
      } else if (err.value instanceof String) {
        AnsiConsole.out().println("      ${err.value}")
      } else if (err.value instanceof Map) {
        err.value.each { key, message ->
          AnsiConsole.out().println("      $key -> $message")
        }
      } else {
        AnsiConsole.out().println("      ${err}")
      }
      AnsiConsole.out().println()
    }
  }

  void displayDiff(err) {
    err.value.comparison.each { key, message ->
      AnsiConsole.out().println("      $key -> $message")
    }

    AnsiConsole.out().println()
    AnsiConsole.out().println('      Diff:')
    AnsiConsole.out().println()

    err.value.diff.each { delta ->
      if (delta.startsWith('@')) {
        AnsiConsole.out().println(Ansi.ansi().a('      ').fg(Ansi.Color.CYAN).a(delta).reset())
      } else if (delta.startsWith('-')) {
        AnsiConsole.out().println(Ansi.ansi().a('      ').fg(Ansi.Color.RED).a(delta).reset())
      } else if (delta.startsWith('+')) {
        AnsiConsole.out().println(Ansi.ansi().a('      ').fg(Ansi.Color.GREEN).a(delta).reset())
      } else {
        AnsiConsole.out().println("      $delta")
      }
    }
  }

  void displayError(Throwable err) {
    err.message.split('\n').each {
      AnsiConsole.out().println("      $it")
    }
  }
}
