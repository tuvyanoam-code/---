package com.tuvyanoam.soundpilot.ui.screens

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tuvyanoam.soundpilot.R
import com.tuvyanoam.soundpilot.container
import com.tuvyanoam.soundpilot.core.model.Condition
import com.tuvyanoam.soundpilot.core.model.Place
import com.tuvyanoam.soundpilot.platform.AddressResult
import com.tuvyanoam.soundpilot.platform.Capability
import com.tuvyanoam.soundpilot.ui.components.AppTopBar
import com.tuvyanoam.soundpilot.ui.components.CapabilityRow
import com.tuvyanoam.soundpilot.ui.components.ConfirmDialog
import com.tuvyanoam.soundpilot.ui.components.EmptyState
import com.tuvyanoam.soundpilot.ui.components.SectionCard
import com.tuvyanoam.soundpilot.ui.components.rememberCapabilityRequester
import com.tuvyanoam.soundpilot.ui.components.rememberMissingCapabilities
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

data class PlaceForm(
    val id: String,
    val name: String,
    val latitude: Double?,
    val longitude: Double?,
    val radius: Int,
    val isNew: Boolean,
    /** Bumped when the coordinates come from outside the text fields, so the fields reload them. */
    val locationVersion: Int = 0,
)

class PlacesViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    val places = c.storage.places.places.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val presence = c.presence.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    var form by mutableStateOf<PlaceForm?>(null)
        private set
    var locating by mutableStateOf(false)
        private set
    var searching by mutableStateOf(false)
        private set
    var results by mutableStateOf<List<AddressResult>>(emptyList())
        private set
    var message by mutableStateOf<String?>(null)
        private set

    fun add() {
        form = PlaceForm("", "", null, null, Place.DEFAULT_RADIUS_METERS, isNew = true)
        results = emptyList()
        message = null
    }

    fun edit(place: Place) {
        form = PlaceForm(place.id, place.name, place.latitude, place.longitude, place.radiusMeters, isNew = false)
        results = emptyList()
        message = null
    }

    fun close() {
        form = null
    }

    fun update(transform: (PlaceForm) -> PlaceForm) {
        form = form?.let(transform)
    }

    fun useCurrentLocation() {
        locating = true
        message = null
        viewModelScope.launch {
            val here = c.location.currentLocation()
            locating = false
            if (here == null) message = getApplication<Application>().getString(R.string.place_no_location)
            else update { it.copy(latitude = here.latitude, longitude = here.longitude, locationVersion = it.locationVersion + 1) }
        }
    }

    fun search(query: String) {
        if (query.isBlank()) return
        searching = true
        message = null
        viewModelScope.launch {
            results = c.location.search(query)
            searching = false
            if (results.isEmpty()) message = getApplication<Application>().getString(R.string.place_no_results)
        }
    }

    fun pick(result: AddressResult) {
        update {
            it.copy(
                latitude = result.latitude,
                longitude = result.longitude,
                name = it.name.ifBlank { result.label.substringBefore(",") },
                locationVersion = it.locationVersion + 1,
            )
        }
        results = emptyList()
    }

    fun save() {
        val f = form ?: return
        val lat = f.latitude
        val lng = f.longitude
        if (f.name.isBlank() || lat == null || lng == null) {
            message = getApplication<Application>().getString(R.string.place_incomplete)
            return
        }
        viewModelScope.launch {
            c.storage.places.upsert(Place(f.id, f.name.trim(), lat, lng, f.radius))
            form = null
            c.geofences.sync()
            c.coordinator.evaluate("place_saved")
        }
    }

    suspend fun rulesUsing(placeId: String): Int =
        c.storage.rules.all().count { rule -> rule.conditions.any { it is Condition.AtPlace && it.placeId == placeId } }

    fun delete() {
        val id = form?.id?.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            c.storage.places.delete(id)
            form = null
            c.geofences.sync()
            c.coordinator.evaluate("place_deleted")
        }
    }
}

@Composable
fun PlacesScreen(openSettings: () -> Unit, vm: PlacesViewModel = viewModel()) {
    val form = vm.form
    if (form != null) {
        BackHandler { vm.close() }
        PlaceEditor(vm, form)
        return
    }
    val places by vm.places.collectAsStateWithLifecycle()
    val presence by vm.presence.collectAsStateWithLifecycle()
    val missing = rememberMissingCapabilities()
    val requester = rememberCapabilityRequester()

    Scaffold(
        topBar = { AppTopBar(stringResource(R.string.tab_places), onSettings = openSettings) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = vm::add, icon = { Icon(Icons.Filled.Add, null) }, text = { Text(stringResource(R.string.place_add)) })
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val locationMissing = listOf(Capability.LOCATION, Capability.BACKGROUND_LOCATION).filter { it in missing }
            if (locationMissing.isNotEmpty()) {
                item {
                    SectionCard(stringResource(R.string.place_location_needed), Icons.Filled.LocationOn) {
                        // Background location is requested only after foreground location was granted.
                        val next = locationMissing.first()
                        CapabilityRow(next, granted = false, onGrant = requester)
                    }
                }
            }
            if (places.isEmpty()) {
                item { EmptyState(Icons.Filled.Place, stringResource(R.string.places_empty_title), stringResource(R.string.places_empty_text)) }
            }
            items(places, key = { it.id }) { place ->
                Card(Modifier.fillMaxWidth().clickable { vm.edit(place) }) {
                    ListItem(
                        headlineContent = { Text(place.name) },
                        supportingContent = {
                            Text(
                                stringResource(
                                    R.string.place_summary,
                                    place.radiusMeters,
                                    String.format(Locale.US, "%.5f, %.5f", place.latitude, place.longitude),
                                ),
                            )
                        },
                        leadingContent = { Icon(Icons.Filled.Place, null) },
                        trailingContent = {
                            if (place.id in presence) SuggestionChip(onClick = {}, label = { Text(stringResource(R.string.place_here)) })
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaceEditor(vm: PlacesViewModel, form: PlaceForm) {
    var query by rememberSaveable { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var usage by remember { mutableIntStateOf(0) }

    if (confirmDelete) {
        ConfirmDialog(
            title = stringResource(R.string.place_delete_title),
            text = stringResource(R.string.place_delete_text, form.name, usage),
            confirm = stringResource(R.string.delete),
            onConfirm = vm::delete,
            onDismiss = { confirmDelete = false },
        )
    }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            AppTopBar(
                title = stringResource(if (form.isNew) R.string.place_add else R.string.place_edit),
                onBack = vm::close,
                actions = {
                    if (!form.isNew) {
                        IconButton(onClick = {
                            scope.launch {
                                usage = vm.rulesUsing(form.id)
                                confirmDelete = true
                            }
                        }) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
                    }
                    TextButton(onClick = vm::save) { Text(stringResource(R.string.save)) }
                },
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = form.name,
                    onValueChange = { name -> vm.update { it.copy(name = name) } },
                    label = { Text(stringResource(R.string.place_name)) },
                    placeholder = { Text(stringResource(R.string.place_name_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                SectionCard(stringResource(R.string.place_location), Icons.Filled.LocationOn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(onClick = vm::useCurrentLocation, enabled = !vm.locating) {
                            Icon(Icons.Filled.MyLocation, null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.place_use_current))
                        }
                        if (vm.locating) {
                            Spacer(Modifier.width(12.dp))
                            CircularProgressIndicator(Modifier.size(24.dp))
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text(stringResource(R.string.place_search)) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                        )
                        IconButton(onClick = { vm.search(query) }, enabled = !vm.searching) {
                            if (vm.searching) CircularProgressIndicator(Modifier.size(24.dp)) else Icon(Icons.Filled.Search, stringResource(R.string.place_search))
                        }
                    }
                    vm.results.forEach { result ->
                        ListItem(
                            headlineContent = { Text(result.label) },
                            leadingContent = { Icon(Icons.Filled.Place, null) },
                            modifier = Modifier.clickable { vm.pick(result) },
                        )
                    }
                    CoordinateFields(form) { lat, lng -> vm.update { it.copy(latitude = lat, longitude = lng) } }
                    vm.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
            item {
                SectionCard(stringResource(R.string.place_radius, form.radius)) {
                    Slider(
                        value = form.radius.toFloat(),
                        onValueChange = { value -> vm.update { it.copy(radius = (value / 25).toInt() * 25) } },
                        valueRange = Place.MIN_RADIUS_METERS.toFloat()..1000f,
                    )
                    Text(stringResource(R.string.place_radius_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CoordinateFields(form: PlaceForm, onChange: (Double?, Double?) -> Unit) {
    var lat by remember(form.locationVersion) { mutableStateOf(form.latitude?.let { String.format(Locale.US, "%.6f", it) }.orEmpty()) }
    var lng by remember(form.locationVersion) { mutableStateOf(form.longitude?.let { String.format(Locale.US, "%.6f", it) }.orEmpty()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = lat,
                onValueChange = {
                    lat = it
                    onChange(it.toDoubleOrNull()?.takeIf { v -> v in -90.0..90.0 }, lng.toDoubleOrNull())
                },
                label = { Text(stringResource(R.string.latitude)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            OutlinedTextField(
                value = lng,
                onValueChange = {
                    lng = it
                    onChange(lat.toDoubleOrNull(), it.toDoubleOrNull()?.takeIf { v -> v in -180.0..180.0 })
                },
                label = { Text(stringResource(R.string.longitude)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
    }
}
