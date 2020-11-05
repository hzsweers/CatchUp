/*
 * Copyright (C) 2020. Zac Sweers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.sweers.catchup.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Dao
interface CatchUpServiceRemoteKeyDao {
  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insert(keys: CatchUpServiceRemoteKey)

  @Query("SELECT * FROM remote_keys WHERE serviceId = :serviceId")
  suspend fun remoteKeyByService(serviceId: String): CatchUpServiceRemoteKey

  @Query("DELETE FROM remote_keys WHERE serviceId = :serviceId")
  suspend fun deleteByService(serviceId: String)
}

@Entity(tableName = "remote_keys")
data class CatchUpServiceRemoteKey(
  @PrimaryKey
  val serviceId: String,
  val nextPageKey: String?
)
