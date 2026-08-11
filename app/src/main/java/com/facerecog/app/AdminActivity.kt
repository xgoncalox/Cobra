package com.facerecog.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.facerecog.app.databinding.ActivityAdminBinding
import kotlinx.coroutines.launch

class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private val repo = FirebaseRepository.getInstance()
    private lateinit var adapter: PersonAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)
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

        loadPersons()
    }

    override fun onResume() {
        super.onResume()
        loadPersons()
    }

    private fun loadPersons() {
        lifecycleScope.launch {
            val persons = repo.getAllPersons().sortedBy { it.name.lowercase() }
            adapter.submitList(persons)
            binding.emptyView.visibility =
                if (persons.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }
    }
}            }
        )

        binding.recyclerView.layoutManager = GridLayoutManager(this, 2)
        binding.recyclerView.adapter = adapter

        lifecycleScope.launch {
            db.personDao().getAllPersons().collect { persons ->
                adapter.submitList(persons)
                binding.emptyView.visibility =
                    if (persons.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
        }
    }
}
