package org.llm4s.agent

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.model.{ UserMessage, SystemMessage, AssistantMessage }

/**
 * Tests for AdaptiveWindowing strategy.
 * Verifies automatic context window optimization based on model capabilities and costs.
 */
class AdaptiveWindowingSpec extends AnyFlatSpec with Matchers {

  "AdaptiveWindowing" should "be constructible with context window size" in {
    val strategy = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 128_000
    )

    strategy.contextWindowSize shouldBe 128_000
    strategy.costSensitivity shouldBe 0.5 // Default
  }

  it should "have sensible defaults" in {
    val strategy = PruningStrategy.AdaptiveWindowing(contextWindowSize = 100_000)

    strategy.preserveMinTurns shouldBe 3
    strategy.costSensitivity shouldBe 0.5
    strategy.inputCostPerToken shouldBe None
    strategy.outputCostPerToken shouldBe None
  }

  it should "reject invalid context window size" in {
    assertThrows[IllegalArgumentException] {
      PruningStrategy.AdaptiveWindowing(contextWindowSize = 0)
    }

    assertThrows[IllegalArgumentException] {
      PruningStrategy.AdaptiveWindowing(contextWindowSize = -1000)
    }
  }

  it should "reject invalid cost sensitivity" in {
    assertThrows[IllegalArgumentException] {
      PruningStrategy.AdaptiveWindowing(
        contextWindowSize = 100_000,
        costSensitivity = -0.1
      )
    }

    assertThrows[IllegalArgumentException] {
      PruningStrategy.AdaptiveWindowing(
        contextWindowSize = 100_000,
        costSensitivity = 1.1
      )
    }
  }

  // ==========================================================================
  // Window Calculation Tests
  // ==========================================================================

  "calculateOptimalWindow" should "use 70% by default for medium models" in {
    val strategy = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 100_000
    )

    val optimal = strategy.calculateOptimalWindow
    optimal shouldBe (100_000 * 0.7).toInt
  }

  it should "apply conservative ratio for small models (≤32K)" in {
    val smallModel = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 8_000
    )

    val optimal = smallModel.calculateOptimalWindow
    // 8K * 0.6 (small tier) = 4,800 tokens
    optimal shouldBe (8_000 * 0.6).toInt
    optimal should be < (8_000 * 0.7).toInt // More conservative than baseline
  }

  it should "apply balanced ratio for medium models (64K-100K)" in {
    val mediumModel = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 100_000
    )

    val optimal = mediumModel.calculateOptimalWindow
    // 100K * 0.7 (medium tier) = 70,000 tokens
    optimal shouldBe (100_000 * 0.7).toInt
  }

  it should "apply generous ratio for large models (200K+)" in {
    val largeModel = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 200_000
    )

    val optimal = largeModel.calculateOptimalWindow
    // 200K * 0.75 (large tier) = 150,000 tokens
    optimal shouldBe (200_000 * 0.75).toInt
    optimal should be > (200_000 * 0.7).toInt
  }

  it should "apply very generous ratio for extra-large models (1M+)" in {
    val extraLargeModel = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 1_000_000
    )

    val optimal = extraLargeModel.calculateOptimalWindow
    // 1M * 0.8 (extra-large tier) = 800,000 tokens
    optimal shouldBe (1_000_000 * 0.8).toInt
  }

  it should "never return less than 1000 tokens" in {
    val tinyModel = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 100 // Unrealistic but test the safeguard
    )

    val optimal = tinyModel.calculateOptimalWindow
    optimal should be >= 1000
  }

  // ==========================================================================
  // Cost Sensitivity Tests
  // ==========================================================================

  it should "reduce window with high cost sensitivity" in {
    val baseline = PruningStrategy
      .AdaptiveWindowing(
        contextWindowSize = 100_000,
        inputCostPerToken = Some(0.000005),
        outputCostPerToken = Some(0.000015),
        costSensitivity = 0.0 // No cost optimization
      )
      .calculateOptimalWindow

    val costOptimized = PruningStrategy
      .AdaptiveWindowing(
        contextWindowSize = 100_000,
        inputCostPerToken = Some(0.000005),
        outputCostPerToken = Some(0.000015),
        costSensitivity = 0.9 // Aggressive cost optimization
      )
      .calculateOptimalWindow

    costOptimized should be < baseline
  }

  it should "not reduce window when no cost data provided" in {
    val withoutCost = PruningStrategy
      .AdaptiveWindowing(
        contextWindowSize = 100_000,
        costSensitivity = 0.9
      )
      .calculateOptimalWindow

    val withoutCost2 = PruningStrategy
      .AdaptiveWindowing(
        contextWindowSize = 100_000,
        inputCostPerToken = Some(0.000005),
        outputCostPerToken = None, // Missing output cost
        costSensitivity = 0.9
      )
      .calculateOptimalWindow

    withoutCost shouldBe withoutCost2
  }

  it should "be more sensitive to input-heavy pricing" in {
    val expensiveInput = PruningStrategy
      .AdaptiveWindowing(
        contextWindowSize = 100_000,
        inputCostPerToken = Some(0.00001),   // Input is expensive
        outputCostPerToken = Some(0.000001), // Output is cheap
        costSensitivity = 1.0
      )
      .calculateOptimalWindow

    val balancedCost = PruningStrategy
      .AdaptiveWindowing(
        contextWindowSize = 100_000,
        inputCostPerToken = Some(0.000005),
        outputCostPerToken = Some(0.000005), // Equal
        costSensitivity = 1.0
      )
      .calculateOptimalWindow

    expensiveInput should be < balancedCost
  }

  // ==========================================================================
  // Explanation and String Representation
  // ==========================================================================

  "explanation" should "describe the calculated window" in {
    val strategy = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 128_000
    )

    val explanation = strategy.explanation
    explanation should include("AdaptiveWindowing")
    explanation should include("128K")
    explanation should include("large")
    explanation should include("%")
  }

  it should "be same as toString" in {
    val strategy = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 100_000
    )

    strategy.toString shouldBe strategy.explanation
  }

  // ==========================================================================
  // Integration with Pruning
  // ==========================================================================

  "AgentState.pruneConversation with AdaptiveWindowing" should "prune using calculated window" in {
    val messages = Seq(
      SystemMessage("You are helpful"),
      UserMessage("Hello"),
      AssistantMessage("Hi there!"),
      UserMessage("Who are you?"),
      AssistantMessage("I'm an assistant"),
      UserMessage("What can you do?"),
      AssistantMessage("I can help with many things"),
      UserMessage("Tell me a story"),
      AssistantMessage("Once upon a time..."),
      UserMessage("More!"),
      AssistantMessage("There was a kingdom...")
    )

    val state = AgentState(
      conversation = org.llm4s.llmconnect.model.Conversation(messages),
      tools = new org.llm4s.toolapi.ToolRegistry(Seq.empty)
    )

    val strategy = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 8_000 // Small model
    )

    val config = ContextWindowConfig(
      pruningStrategy = strategy,
      preserveSystemMessage = true
    )

    val pruned = AgentState.pruneConversation(state, config)

    // Should have fewer messages after pruning
    pruned.conversation.messages.length should be <= messages.length
    // System message should be preserved
    pruned.conversation.messages.head shouldBe messages.head
  }

  it should "respect minimum recent turns" in {
    val messages = (1 to 20).flatMap { i =>
      Seq(
        UserMessage(s"Question $i"),
        AssistantMessage(s"Answer $i")
      )
    }

    val state = AgentState(
      conversation = org.llm4s.llmconnect.model.Conversation(messages),
      tools = new org.llm4s.toolapi.ToolRegistry(Seq.empty)
    )

    val strategy = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 4_000,
      preserveMinTurns = 5
    )

    val config = ContextWindowConfig(
      pruningStrategy = strategy,
      minRecentTurns = 5 // Keep at least 5 turns
    )

    val pruned = AgentState.pruneConversation(state, config)

    // Should preserve roughly last 5 turns (10 messages)
    pruned.conversation.messages.length should be >= 10
  }

  // ==========================================================================
  // Realistic Scenarios
  // ==========================================================================

  "AdaptiveWindowing for GPT-4o" should "use appropriate window" in {
    val gpt4o = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 128_000,
      inputCostPerToken = Some(0.0000025), // $2.50 per 1M input tokens
      outputCostPerToken = Some(0.000010), // $10 per 1M output tokens
      costSensitivity = 0.5                // Balanced
    )

    val window = gpt4o.calculateOptimalWindow
    println(s"GPT-4o optimal window: $window tokens")

    // Should be something like 89,600 (70% of 128K)
    window should be > 80_000
    window should be < 100_000
    window should be < 128_000 // Less than full context
  }

  it should "be more conservative when input is expensive" in {
    val expensive = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 128_000,
      inputCostPerToken = Some(0.00001), // Expensive!
      outputCostPerToken = Some(0.000001),
      costSensitivity = 0.8
    )

    val cheap = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 128_000,
      inputCostPerToken = Some(0.000001),
      outputCostPerToken = Some(0.00001), // Expensive output
      costSensitivity = 0.8
    )

    expensive.calculateOptimalWindow should be < cheap.calculateOptimalWindow
  }

  "AdaptiveWindowing for Claude 3.5" should "use appropriate window" in {
    val claude = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 200_000,
      inputCostPerToken = Some(0.000003),  // $3 per 1M input tokens
      outputCostPerToken = Some(0.000015), // $15 per 1M output tokens
      costSensitivity = 0.5
    )

    val window = claude.calculateOptimalWindow
    println(s"Claude 3.5 optimal window: $window tokens")

    // Should be 70-75% of 200K
    window should be > 140_000
    window should be < 160_000
  }

  "AdaptiveWindowing for Ollama local" should "use large window" in {
    val ollama = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 4_096,
      inputCostPerToken = None, // No cost (local)
      outputCostPerToken = None,
      costSensitivity = 0.5
    )

    val window = ollama.calculateOptimalWindow
    println(s"Ollama optimal window: $window tokens")

    // Should use 60-70% for small model
    window should be > 2_000
    window should be < 3_000
  }

  // ==========================================================================
  // Copy and Equality
  // ==========================================================================

  "AdaptiveWindowing" should "support copy for modifications" in {
    val original = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 100_000,
      costSensitivity = 0.5
    )

    val modified = original.copy(costSensitivity = 0.8)

    modified.contextWindowSize shouldBe original.contextWindowSize
    modified.costSensitivity should not be original.costSensitivity
    modified.costSensitivity shouldBe 0.8
  }

  it should "support equality" in {
    val s1 = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 100_000,
      costSensitivity = 0.5
    )

    val s2 = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 100_000,
      costSensitivity = 0.5
    )

    val s3 = PruningStrategy.AdaptiveWindowing(
      contextWindowSize = 100_000,
      costSensitivity = 0.8
    )

    s1 shouldBe s2
    s1 should not be s3
  }
}
