package me.blvckbytes.channel_downloader

import me.blvckbytes.channel_downloader.model.Identifiable

object ExtensionUtil {
  class ExtensionResult(
    countMissingLocally: Int = 0,
    countMissingRemotely: Int = 0,
    countCommon: Int = 0,
  ) {
    var countMissingLocally = countMissingLocally
      private set

    var countMissingRemotely = countMissingRemotely
      private set

    var countCommon = countCommon
      private set

    fun extendBy(other: ExtensionResult) {
      this.countMissingLocally += other.countMissingLocally
      this.countMissingRemotely += other.countMissingRemotely
      this.countCommon += other.countCommon
    }

    override fun toString(): String {
      return "ExtensionResult(missingLocally=$countMissingLocally, missingRemotely=$countMissingRemotely, common=$countCommon)"
    }
  }

  /**
   * @param containedCallback Return null if the item stays; otherwise, return an object
   *                          to put it in place of the contained item
   */
  inline fun <T : Identifiable> extend(
    localData: Iterator<T>,
    remoteData: Iterator<T>,
    containedCallback: (localItem: T, remoteItem: T) -> T? = { _, _ -> null },
    preAddCallback: (addedItem: T) -> T = { it },
  ): Pair<ExtensionResult, Collection<T>> {
    val localSet = HashMap<String, T>()
    val remoteSet = HashMap<String, T>()

    localData.forEach { localSet[it.id] = it }
    remoteData.forEach { remoteSet[it.id] = it }

    val initialLocalSetSize = localSet.size
    var missingRemotely = 0

    for (localKey in localSet.keys) {
      if (!remoteSet.containsKey(localKey))
        ++missingRemotely
    }

    var missingLocally = 0

    for (remoteEntry in remoteSet) {
      val localItem = localSet[remoteEntry.key]

      if (localItem != null) {
        val substitution = containedCallback(localItem, remoteEntry.value) ?: continue

        if (substitution.id != localItem.id)
          throw IllegalStateException("Expected id=${substitution.id} of the substitution to equal id=${localItem.id} of the existing item")

        localSet[remoteEntry.key] = substitution
        continue
      }

      localSet[remoteEntry.key] = preAddCallback(remoteEntry.value)
      ++missingLocally
    }

    return Pair(ExtensionResult(missingLocally, missingRemotely, initialLocalSetSize - missingRemotely), localSet.values)
  }
}