package com.facerecog.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.facerecog.app.databinding.ActivityAdminBinding
import kotlinx.coroutines.launch

class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private val repo = SupabaseRepository.getInstance()
    private lateinit var adapter: PersonAdapter
    private var allPersons: List<Person> = emptyList()
    private var currentQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }
        supportActionBar?.title = "Admin — People"

        adapter = PersonAdapter(
            onClick = { person ->
                val intent = Intent(this, PersonDetailActivity::class.java)
                intent.putExtra("personId", person.id)
                intent.putExtra("personName", person.name)
                startActivity(intent)
            },
            onDelete = { person ->
                lifecycleScope.launch {
                    repo.deletePerson(person.id)
                    loadPersons()
                }
            }
        )

        binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
        binding.recyclerView.adapter = adapter

        binding.editSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString().orEmpty()
                applyFilter()
            }
        })

        loadPersons()
    }

    override fun onResume() {
        super.onResume()
        loadPersons()
    }

    private fun loadPersons() {
        lifecycleScope.launch {
            allPersons = repo.getAllPersons().sortedBy { it.name.lowercase() }
            applyFilter()
        }
    }

    private fun applyFilter() {
        val filtered = if (currentQuery.isBlank()) {
            allPersons
        } else {
            allPersons.filter { it.name.contains(currentQuery, ignoreCase = true) }
        }
        adapter.submitList(filtered)

        binding.emptyView.visibility =
            if (filtered.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

        if (filtered.isEmpty()) {
            if (allPersons.isEmpty()) {
                binding.tvEmptyTitle.text = "No people yet"
                binding.tvEmptySubtitle.text = "Assign an unknown face from the camera screen to get started"
            } else {
                binding.tvEmptyTitle.text = "No matches"
                binding.tvEmptySubtitle.text = "No one named \"$currentQuery\" yet"
            }
        }
    }
}
