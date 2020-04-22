package net.vonforst.evmap.viewmodel

import android.app.Application
import androidx.lifecycle.*
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import net.vonforst.evmap.adapter.Equatable
import net.vonforst.evmap.api.availability.ChargeLocationStatus
import net.vonforst.evmap.api.availability.ChargepointStatus
import net.vonforst.evmap.api.availability.getAvailability
import net.vonforst.evmap.api.distanceBetween
import net.vonforst.evmap.api.goingelectric.ChargeLocation
import net.vonforst.evmap.api.goingelectric.GoingElectricApi
import net.vonforst.evmap.storage.AppDatabase

class FavoritesViewModel(application: Application, geApiKey: String) :
    AndroidViewModel(application) {
    private var api = GoingElectricApi.create(geApiKey)
    private var db = AppDatabase.getInstance(application)

    val favorites: LiveData<List<ChargeLocation>> by lazy {
        db.chargeLocationsDao().getAllChargeLocations()
    }

    val location: MutableLiveData<LatLng> by lazy {
        MutableLiveData<LatLng>()
    }

    val availability: MediatorLiveData<Map<Long, Resource<ChargeLocationStatus>>> by lazy {
        MediatorLiveData<Map<Long, Resource<ChargeLocationStatus>>>().apply {
            addSource(favorites) { chargers ->
                if (chargers != null) {
                    viewModelScope.launch {
                        val data = hashMapOf<Long, Resource<ChargeLocationStatus>>()
                        chargers.forEach { charger ->
                            data[charger.id] = Resource.loading(null)
                        }
                        availability.value = data

                        chargers.map { charger ->
                            async {
                                data[charger.id] = getAvailability(charger)
                                availability.value = data
                            }
                        }.awaitAll()
                    }
                } else {
                    value = null
                }
            }
        }
    }

    val listData: MediatorLiveData<List<FavoritesListItem>> by lazy {
        MediatorLiveData<List<FavoritesListItem>>().apply {
            val callback = { _: Any ->
                listData.value = favorites.value?.map { charger ->
                    FavoritesListItem(
                        charger,
                        totalAvailable(charger.id),
                        charger.chargepoints.sumBy { it.count },
                        location.value.let { loc ->
                            if (loc == null) null else {
                                distanceBetween(
                                    loc.latitude,
                                    loc.longitude,
                                    charger.coordinates.lat,
                                    charger.coordinates.lng
                                ) / 1000
                            }
                        })
                }
            }
            addSource(favorites, callback)
            addSource(location, callback)
            addSource(availability, callback)
        }
    }

    data class FavoritesListItem(
        val charger: ChargeLocation,
        val available: Int?,
        val total: Int,
        val distance: Double?
    ) : Equatable

    private fun totalAvailable(id: Long): Int? {
        val values = availability.value?.get(id)?.data?.status?.values ?: return null
        return values.sumBy { it.filter { it == ChargepointStatus.AVAILABLE }.size }
    }

    fun insertFavorite(charger: ChargeLocation) {
        viewModelScope.launch {
            db.chargeLocationsDao().insert(charger)
        }
    }

    fun deleteFavorite(charger: ChargeLocation) {
        viewModelScope.launch {
            db.chargeLocationsDao().delete(charger)
        }
    }
}