# Context Window Pruning Strategies

Complete guide to LLM4S context window management and pruning strategies.

## Overview

As conversations grow, token counts increase, leading to:
- **Higher costs** - Most LLM providers charge per token
- **Slower responses** - Longer context = slower processing
- **Context window limits** - Models have maximum input token limits

LLM4S provides automatic context window management via `ContextWindowConfig` with multiple pruning strategies to keep conversations within budget while preserving important context.

## Configuration Basics

```scala
import org.llm4s.agent.{ Agent, ContextWindowConfig, PruningStrategy }

val contextConfig = ContextWindowConfig(
  maxTokens = Some(4096),              // Keep max 4K tokens
  maxMessages = Some(20),              // OR max 20 messages
  preserveSystemMessage = true,        // Always keep system prompt
  minRecentTurns = 3,                  // Keep last 3 user/assistant pairs
  pruningStrategy = PruningStrategy.OldestFirst  // How to prune
)

agent.runMultiTurn(
  initialQuery = "...",
  followUpQueries = Seq(...),
  tools = tools,
  contextWindowConfig = Some(contextConfig)
)
```

## Pruning Strategies

### 1. OldestFirst (Recommended for most use cases)

**How it works:** Removes the oldest messages first while preserving the system message and most recent turns.

**Best for:**
- ✅ General-purpose multi-turn conversations
- ✅ FAQ-bot with rolling history
- ✅ Customer support chats
- ✅ When you want to keep latest context fresh

**Cost:**
- 🟢 Good - Removes old/repetitive context first
- 🟢 Predictable token savings

**Quality:**
- 🟡 Medium - May lose context from early turns
- ✅ Recent context stays intact

**Example:**
```scala
val config = ContextWindowConfig(
  maxMessages = Some(15),
  pruningStrategy = PruningStrategy.OldestFirst
)

// Conversation with 30 messages →  last 15 messages kept
// System message is always preserved
```

**Trade-offs:**
| Aspect | Rating | Notes |
|--------|--------|-------|
| Token efficiency | ⭐⭐⭐⭐ | Removes metadata/greetings first |
| Context continuity | ⭐⭐⭐ | Recent context preserved |
| Implementation | ⭐⭐⭐⭐⭐ | Simple FIFO removal |
| Use case fit | Broad | Works for most scenarios |

---

### 2. MiddleOut (For complex reasoning)

**How it works:** Keeps the start (system + initial setup) and end (recent exchanges), removes middle messages.

**Best for:**
- ✅ Complex problem-solving requiring both initial context and recent progress
- ✅ Long code reviews or technical documents
- ✅ When you need to preserve both "setup" and "current state"
- ✅ Scientific or analytical conversations

**Cost:**
- 🟡 Medium - Loses middle context but keeps important bookends
- 🟡 Less predictable than OldestFirst

**Quality:**
- 🟡 Medium - Loses intermediate steps
- ✅ Good - Keeps initial and recent context

**Example:**
```scala
val config = ContextWindowConfig(
  maxMessages = Some(20),
  pruningStrategy = PruningStrategy.MiddleOut
)

// Conversation:
// [System] [Initial Context] [Step 1] [Step 2] ... [Step N] [Recent Q&A]
//                                      ↑                    ↑
//                         These middle steps are pruned; keeps ends
```

**Trade-offs:**
| Aspect | Rating | Notes |
|--------|--------|-------|
| Token efficiency | ⭐⭐⭐ | Medium removal |
| Context continuity | ⭐⭐⭐⭐ | Preserves bookends |
| Implementation | ⭐⭐⭐⭐ | Straightforward middle removal |
| Use case fit | Specialized | Good for reasoning chains |

---

### 3. RecentTurnsOnly (For strict turn-based systems)

**How it works:** Keeps only the last N complete conversation turns (user message + assistant response + tool calls).

**Best for:**
- ✅ Turn-based games or dialogues
- ✅ Systems that value latest N interactions equally
- ✅ When conversation structure matters more than time
- ✅ Predictable conversation patterns

**Cost:**
- 🟢 Good - Focused token reduction
- ✅ Very predictable

**Quality:**
- 🟡 Medium - Only recent exchanges (may need earlier context)
- ✅ Good - Full recent turns preserved

**Example:**
```scala
val config = ContextWindowConfig(
  pruningStrategy = PruningStrategy.RecentTurnsOnly(5)
)

// Keeps exactly the last 5 user/assistant turn pairs
// A "turn" = [User message] → [Assistant response] + optional [Tool messages]

// Turn 1: User asks → Assistant responds (+ tool calls)
// Turn 2: User asks → Assistant responds (+ tool calls)
// ...
// Turn 5: User asks → Assistant responds (+ tool calls)
// Turns 1-N: Pruned
```

**Trade-offs:**
| Aspect | Rating | Notes |
|--------|--------|-------|
| Token efficiency | ⭐⭐⭐⭐ | Removes entire turn groups |
| Context continuity | ⭐⭐ | May lose early turns |
| Implementation | ⭐⭐⭐⭐ | Turn-based filtering |
| Use case fit | Specific | Best for turn-based systems |

---

### 4. Custom (Maximum flexibility)

**How it works:** You provide a pure function that decides which messages to keep.

**Best for:**
- ✅ Domain-specific pruning logic
- ✅ Marketing messages removal
- ✅ Metadata-first removal
- ✅ Complex importance scoring
- ✅ Hybrid strategies

**Cost:**
- 🟢 Excellent - You decide what's expensive
- ⭐ Depends on your logic

**Quality:**
- 🟢 Excellent - You decide what matters
- ⭐ Depends on your logic

**Example:**
```scala
// Remove verbose debug messages, keep concise ones
val pruneDebugMessages: Seq[Message] => Seq[Message] = { messages =>
  messages.filter { msg =>
    !msg.content.toLowerCase.contains("[debug]") ||
    msg.content.length < 100  // Keep short debug messages
  }
}

val config = ContextWindowConfig(
  maxMessages = Some(50),
  pruningStrategy = PruningStrategy.Custom(pruneDebugMessages)
)
```

**Advanced Example (importance scoring):**
```scala
val scoreImportance: Seq[Message] => Seq[Message] = { messages =>
  messages.map { msg =>
    val score = msg.role match {
      case MessageRole.System      => 100  // Always keep
      case MessageRole.User        => 80   // Keep user messages
      case MessageRole.Assistant   => 70   // Keep responses
      case MessageRole.Tool        => 40   // Lower priority
      case _                       => 0
    }
    (msg, score)
  }
  .sortBy(_._2)  // Sort by importance
  .dropWhile(_ => tokenCount > limit)  // Remove until under limit
  .map(_._1)
}
```

**Trade-offs:**
| Aspect | Rating | Notes |
|--------|--------|-------|
| Token efficiency | ⭐⭐⭐⭐⭐ | Complete control |
| Context continuity | ⭐⭐⭐⭐⭐ | Complete control |
| Implementation | ⭐⭐ | Requires custom code |
| Use case fit | Very specific | Best for special requirements |

---

### 5. AdaptiveWindowing (Intelligent auto-tuning)

**How it works:** Automatically calculates the optimal context window based on:
- Model's context size
- Model tier (small, medium, large, extra-large)
- Token pricing (input vs output cost)
- Cost sensitivity preference

**Best for:**
- ✅ Multi-model deployments (switch models without config changes)
- ✅ Cost-conscious production systems
- ✅ Quality-sensitive applications
- ✅ Hybrid cost/quality optimization
- ✅ When you want "set it and forget it"

**Cost:**
- 🟢 Excellent - Optimizes based on actual pricing
- ✅ Adapts to model changes

**Quality:**
- 🟢 Good - Uses 70-80% of context window
- ✅ Leaves room for model reasoning

**Example:**
```scala
import org.llm4s.model.ModelMetadata
import org.llm4s.agent.PruningStrategy

// From ModelMetadata (automatically available)
val strategy = PruningStrategy.AdaptiveWindowing(
  contextWindowSize = 128_000,           // gpt-4o context
  inputCostPerToken = Some(0.000005),    // $5 per 1M input tokens
  outputCostPerToken = Some(0.000015),   // $15 per 1M output tokens
  preserveMinTurns = 3,
  costSensitivity = 0.5  // Balanced (0=quality, 1=cost)
)

val config = ContextWindowConfig(
  pruningStrategy = strategy
)

println(strategy.calculateOptimalWindow)  // Output: 89,600 tokens (70% * 128K)
println(strategy.explanation)  // "AdaptiveWindowing: using 89,600 tokens (70% of 128K large model)"
```

**Model tier adjustments:**

| Model Size | Context | Multiplier | Adjusted Window | Use Case |
|-----------|---------|-----------|-----------------|----------|
| Small | 8K | 60% | 4.8K | Budget-conscious |
| Medium | 100K | 70% | 70K | Balanced |
| Large | 200K | 75% | 150K | Quality-focused |
| Extra Large | 1M+ | 80% | 800K+ | Complex reasoning |

**Cost sensitivity examples:**

```scala
// 🟡 Balanced (default)
// Sensitivity = 0.5
// Minimizes cost moderately while preserving quality

val balanced = PruningStrategy.AdaptiveWindowing(
  contextWindowSize = 128_000,
  inputCostPerToken = Some(0.000005),
  outputCostPerToken = Some(0.000015),
  costSensitivity = 0.5
)

// 🟢 Quality-focused
// Sensitivity = 0.1
// Use most of the available context, cost matters less
val qualityFocused = PruningStrategy.AdaptiveWindowing(
  contextWindowSize = 128_000,
  costSensitivity = 0.1  // Low cost sensitivity
)

// 💰 Cost-optimized
// Sensitivity = 0.9
// Aggressively minimize token usage
val costOptimized = PruningStrategy.AdaptiveWindowing(
  contextWindowSize = 128_000,
  costSensitivity = 0.9  // High cost sensitivity
)
```

**Trade-offs:**
| Aspect | Rating | Notes |
|--------|--------|-------|
| Token efficiency | ⭐⭐⭐⭐ | Auto-optimized |
| Context continuity | ⭐⭐⭐⭐ | Preserves minimum turns |
| Implementation | ⭐⭐⭐⭐⭐ | Recommended for production |
| Use case fit | Universal | Works everywhere |

---

## Strategy Comparison Matrix

| Strategy | Pros | Cons | Cost | Quality | When to Use |
|----------|------|------|------|---------|------------|
| **OldestFirst** | Simple, predictable | May lose early context | ⭐⭐⭐⭐ | ⭐⭐⭐ | Default choice |
| **MiddleOut** | Preserves bookends | Loses intermediate steps | ⭐⭐⭐ | ⭐⭐⭐⭐ | Complex reasoning |
| **RecentTurnsOnly** | Consistent turn count | Loses older turns | ⭐⭐⭐⭐ | ⭐⭐ | Turn-based systems |
| **Custom** | Maximum flexibility | Requires implementation | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | Special needs |
| **AdaptiveWindowing** | Auto-optimized, scales | Needs model metadata | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ | Production systems |

---

## Configuration Patterns

### Pattern 1: Token-based Limiting (Recommended)

```scala
val config = ContextWindowConfig(
  maxTokens = Some(4096),
  pruningStrategy = PruningStrategy.OldestFirst
)
// More precise - based on actual token usage
// Handles variable-length messages correctly
```

### Pattern 2: Message-based Limiting (Simpler)

```scala
val config = ContextWindowConfig(
  maxMessages = Some(20),
  pruningStrategy = PruningStrategy.OldestFirst
)
// Simpler to understand
// Less precise but easier to reason about
```

### Pattern 3: Hybrid (Both limits)

```scala
val config = ContextWindowConfig(
  maxTokens = Some(4096),
  maxMessages = Some(50),
  pruningStrategy = PruningStrategy.OldestFirst
)
// Enforces BOTH limits
// Prunes when either limit exceeded
```

### Pattern 4: Preserve Minimum Context

```scala
val config = ContextWindowConfig(
  maxMessages = Some(15),
  preserveSystemMessage = true,
  minRecentTurns = 3,                    // Always keep last 3 turns
  pruningStrategy = PruningStrategy.RecentTurnsOnly(5)
)
// Guarantees minimum context is always available
// Even if other limits would be exceeded
```

### Pattern 5: Adaptive Mode (Multi-model)

```scala
val strategy = if (isExpensiveModel) {
  PruningStrategy.AdaptiveWindowing(
    contextWindowSize = modelSize,
    inputCostPerToken = Some(0.000010),
    costSensitivity = 0.8  // Aggressive cost optimization
  )
} else {
  PruningStrategy.AdaptiveWindowing(
    contextWindowSize = modelSize,
    inputCostPerToken = Some(0.000001),
    costSensitivity = 0.3  // More quality-focused
  )
}

val config = ContextWindowConfig(
  pruningStrategy = strategy
)
```

---

## Cost Analysis

### Calculate conversation cost

```scala
val tokenCounter = ConversationTokenCounter.forModel("gpt-4o").get
val conversationTokens = tokenCounter.countConversation(conversation)

// Cost calculation
val inputCostPerToken = 0.000005  // $5 per 1M tokens
val totalCost = conversationTokens * inputCostPerToken

println(s"Conversation: $conversationTokens tokens = \$$totalCost")
```

### Compare strategies

```scala
// Without pruning: 10,000 tokens = $0.05
// With OldestFirst (keeping 50 messages): ~3,000 tokens = $0.015 (70% savings)
// With AdaptiveWindowing: ~4,096 tokens = $0.020 (60% savings)

// For 100 conversations per day:
// Without: $5.00/day
// With pruning: $1.50-2.00/day (savings: $3-3.50/day)
```

---

## Best Practices

### ✅ DO

1. **Use token-based limits** - More accurate for variable-length conversations
   ```scala
   maxTokens = Some(4096)  // Better than maxMessages
   ```

2. **Preserve system message** - Keeps instructions intact
   ```scala
   preserveSystemMessage = true  // Default, always set this
   ```

3. **Use AdaptiveWindowing for production** - Handles model changes automatically
   ```scala
   PruningStrategy.AdaptiveWindowing(contextWindowSize, costPerToken)
   ```

4. **Monitor pruning events** - Log when and what gets removed
   ```scala
   // See next section on observability
   ```

5. **Test with real conversations** - Benchmark different strategies

### ❌ DON'T

1. **Set maxTokens too low** - Breaks context coherence
   ```scala
   maxTokens = Some(256)  // Too small!
   ```

2. **Forget about output budget** - Leave room for model responses
   ```scala
   // If context is 100K, use ~70K for input (leave 30K for output)
   maxTokens = Some(70_000)
   ```

3. **Use token limits without a tokenizer** - Falls back to word count estimation
   ```scala
   // Integrate ConversationTokenCounter for accuracy
   ```

4. **Hard-code window sizes** - Use AdaptiveWindowing instead
   ```scala
   // Bad: Fixed 4K window works only for specific models
   // Good: AdaptiveWindowing scales with model changes
   ```

---

## Observability & Monitoring

### Track pruning events

```scala
agent.runWithEvents(query, tools, contextConfig = Some(config)) { event =>
  event match {
    case ContextWindowPruned(before, after, strategy) =>
      logger.info(s"Pruned: ${before.length} → ${after.length} messages")
      logger.info(s"Strategy: $strategy")
      logger.info(s"Tokens removed: estimate")
    case _ => ()
  }
}
```

### Log pruning performance

```scala
val pruned = AgentState.pruneConversation(state, config, tokenCounter)
val tokenBefore = tokenCounter.countConversation(state.conversation)
val tokenAfter = tokenCounter.countConversation(pruned.conversation)

logger.info(s"Pruning efficiency: saved ${tokenBefore - tokenAfter} tokens")
logger.info(s"Cost savings: $${(tokenBefore - tokenAfter) * costPerToken}")
```

---

## Troubleshooting

### Issue: Conversation keeps growing despite pruning

**Cause:** `contextWindowConfig` not passed to agent method

**Solution:**
```scala
// ❌ Wrong: pruning not enabled
agent.runMultiTurn(query, followUps, tools)

// ✅ Right: pruning enabled
agent.runMultiTurn(
  query, 
  followUps, 
  tools,
  contextWindowConfig = Some(config)  // Must specify!
)
```

### Issue: Important context is being removed

**Cause:** Too aggressive pruning strategy

**Solution:**
```scala
// Increase limits
val config = ContextWindowConfig(
  maxTokens = Some(8192),  // Was 4096
  pruningStrategy = PruningStrategy.MiddleOut  // Preserves bookends
)

// Or use Custom strategy to protect important messages
```

### Issue: Conversations are too short

**Cause:** minRecentTurns or preserveSystemMessage settings

**Solution:**
```scala
val config = ContextWindowConfig(
  maxTokens = Some(8192),
  minRecentTurns = 1,              // Fewer forced turns
  preserveSystemMessage = false,   // Allow system to be pruned if needed
  pruningStrategy = PruningStrategy.OldestFirst
)
```

---

## Advanced Examples

### Example 1: Cost-conscious chatbot

```scala
val config = ContextWindowConfig(
  pruningStrategy = PruningStrategy.AdaptiveWindowing(
    contextWindowSize = 8000,              // Smaller model
    inputCostPerToken = Some(0.00001),     // Expensive
    outputCostPerToken = Some(0.00003),
    costSensitivity = 0.9                  // Minimize cost
  )
)
```

### Example 2: Research assistant (preserve all context)

```scala
val config = ContextWindowConfig(
  maxTokens = Some(200_000),               // Large window
  pruningStrategy = PruningStrategy.MiddleOut,  // Keep setup + recent
  preserveSystemMessage = true
)
```

### Example 3: Code review assistant

```scala
val config = ContextWindowConfig(
  pruningStrategy = PruningStrategy.Custom { messages =>
    messages.filter { msg =>
      // Keep code snippets and review comments
      msg.role == MessageRole.System ||      // Keep instructions
      msg.content.contains("```") ||         // Keep code blocks
      msg.content.contains("review") ||      // Keep review comments
      messages.indexOf(msg) >= messages.length - 10  // Keep recent
    }
  }
)
```

### Example 4: Turn-based game

```scala
val config = ContextWindowConfig(
  pruningStrategy = PruningStrategy.RecentTurnsOnly(10),  // Last 10 turns
  preserveSystemMessage = true,
  minRecentTurns = 1  // Always keep last turn
)
```

---

## Summary

| Need | Strategy | Reason |
|------|----------|--------|
| I just want a working solution | **OldestFirst** | Simplest, most reliable |
| I'm building a production system | **AdaptiveWindowing** | Auto-scales with models |
| I have complex reasoning | **MiddleOut** | Preserves bookends |
| I need precise control | **Custom** | Maximum flexibility |
| I have turn-based logic | **RecentTurnsOnly** | Natural for turns |

Choose **AdaptiveWindowing** for production systems that work across multiple models. Choose **OldestFirst** for everything else.
