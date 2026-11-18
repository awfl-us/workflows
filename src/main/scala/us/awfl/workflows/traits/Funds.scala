package us.awfl.workflows.traits

import us.awfl.workflows.EventHandler

// Temporary OpenAI prompt-cache experiment: omit both fund-related system
// messages from the model prompt. Underlying spending accounting/limits remain
// in EventHandler and the LLM service, unaffected by this prompt-only change.
trait Funds extends EventHandler
